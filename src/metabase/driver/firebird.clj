(ns metabase.driver.firebird
  "Driver Firebird para Metabase 0.61+ (HoneySQL 2, compilação MBQL 5), focado em Firebird 2.5.

  Usa o driver abstrato `:sql-mbql5`: as cláusulas MBQL chegam no formato `[tag opções & args]`.

  Diferenças do Firebird 2.5 tratadas aqui:
  - sem LIMIT/OFFSET: usa `ROWS m TO n`;
  - sem tipo BOOLEAN: usa 1/0 e comparações;
  - sem date_trunc: truncamento via DATEADD/EXTRACT;
  - sem funções de janela, STDDEV, PERCENTILE e REGEX (recursos desligados);
  - SUBSTRING com FROM/FOR, concatenação com `||`, identificadores de até 31 bytes;
  - tipagem fraca de parâmetros `?`: as consultas do construtor visual são geradas com valores inline."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [honey.sql :as sql]
   [honey.sql.helpers :as sql.helpers]
   [java-time.api :as t]
   [metabase.driver :as driver]
   [metabase.driver.common :as driver.common]
   [metabase.driver.impl :as driver.impl]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-mbql5]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x])
  (:import
   (java.time OffsetDateTime OffsetTime ZonedDateTime)))

(set! *warn-on-reflection* true)

(driver/register! :firebird, :parent #{:sql-jdbc :sql-mbql5})

;;; ------------------------------------------------- Recursos -------------------------------------------------

(doseq [[feature supported?] {:schemas                                false
                              :now                                    true
                              :identifiers-with-spaces                true
                              :case-sensitivity-string-filter-options true
                              :full-join                              true
                              :right-join                             true
                              ;; Firebird 2.5 não tem funções de janela, STDDEV/VAR, PERCENTILE nem REGEX
                              :window-functions/cumulative            false
                              :window-functions/offset                false
                              :standard-deviation-aggregations        false
                              :percentile-aggregations                false
                              :regex                                  false
                              :convert-timezone                       false
                              :datetime-diff                          false
                              :uuid-type                              false
                              :split-part                             false
                              :database-routing                       false
                              :connection-impersonation               false
                              :upload-with-auto-pk                    false
                              :index-info                             false
                              :test/jvm-timezone-setting              false}]
  (defmethod driver/database-supports? [:firebird feature] [_driver _feature _db] supported?))

(defmethod driver/db-start-of-week :firebird
  [_driver]
  ;; EXTRACT(WEEKDAY ...) devolve 0 = domingo
  :sunday)

;; Firebird 2.5 aceita identificadores de no máximo 31 bytes, inclusive aliases gerados pelo Metabase
(defmethod driver/escape-alias :firebird
  [_driver alias-name]
  (driver.impl/truncate-alias alias-name 31))

;;; ------------------------------------------------- Conexão -------------------------------------------------

(defn- port->int [port]
  (cond
    (string? port) (Integer/parseInt (str/trim port))
    (some? port)   port
    :else          3050))

(defmethod sql-jdbc.conn/connection-details->spec :firebird
  [_driver {:keys [host port dbname db charset], :as details}]
  (let [database (or (not-empty dbname) (not-empty db) "")
        charset  (or (some-> charset str/trim not-empty) "WIN1252")]
    (-> {:classname   "org.firebirdsql.jdbc.FBDriver"
         :subprotocol "firebirdsql"
         :subname     (str "//" (or (not-empty host) "localhost") ":" (port->int port) "/" database)
         :encoding    charset}
        (merge (select-keys details [:user :password]))
        (sql-jdbc.common/handle-additional-options details))))

(defmethod driver/can-connect? :firebird
  [driver details]
  (sql-jdbc.conn/with-connection-spec-for-testing-connection [jdbc-spec [driver details]]
    (let [[row] (jdbc/query jdbc-spec ["SELECT 1 AS R FROM RDB$DATABASE"])]
      (= 1 (first (vals row))))))

;;; -------------------------------------------------- Sync --------------------------------------------------

;; Firebird não tem schemas: busca todas as tabelas/views e filtra depois
(defmethod sql-jdbc.sync/active-tables :firebird
  [& args]
  (apply sql-jdbc.sync/post-filtered-active-tables args))

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)BLOB SUB_TYPE (1|TEXT)" :type/Text]
    [#"(?i)BLOB"                   :type/*]
    [#"(?i)INT128"                 :type/Decimal]
    [#"(?i)BIGINT|INT64"           :type/BigInteger]
    [#"(?i)SMALLINT"               :type/Integer]
    [#"(?i)INTEGER"                :type/Integer]
    [#"(?i)NUMERIC|DECIMAL"        :type/Decimal]
    [#"(?i)DECFLOAT"               :type/Decimal]
    [#"(?i)DOUBLE"                 :type/Float]
    [#"(?i)FLOAT"                  :type/Float]
    [#"(?i)CHAR"                   :type/Text]
    [#"(?i)TIMESTAMP WITH"         :type/DateTimeWithLocalTZ]
    [#"(?i)TIMESTAMP"              :type/DateTime]
    [#"(?i)DATE"                   :type/Date]
    [#"(?i)TIME WITH"              :type/TimeWithLocalTZ]
    [#"(?i)TIME"                   :type/Time]
    [#"(?i)BOOLEAN"                :type/Boolean]]))

(defmethod sql-jdbc.sync/database-type->base-type :firebird
  [_driver database-type]
  (database-type->base-type database-type))

;;; ------------------------------------------- Compilação de SQL -------------------------------------------

;; Firebird 2.5 não aceita comparações como valor (`SELECT a > b`, `GROUP BY a > b`): só em WHERE/HAVING/CASE.
;; Expressões booleanas usadas como coluna viram `CASE WHEN a > b THEN 1 ELSE 0 END`.
(def ^:private predicate-ops
  #{:= :<> :!= :not= :< :> :<= :>= :and :or :not :like :not-like :between :in :not-in :is :is-not :exists})

(defn- predicate? [expr]
  (let [x (h2x/unwrap-typed-honeysql-form expr)]
    (and (vector? x) (contains? predicate-ops (first x)))))

(defn- predicate->int [expr]
  (if (predicate? expr)
    [:case expr [:inline 1] :else [:inline 0]]
    expr))

(defn- fix-predicate-columns [query]
  (cond-> query
    (:select query)   (update :select (partial mapv (fn [item]
                                                      (if (and (vector? item) (vector? (second item)))
                                                        (update item 0 predicate->int)
                                                        (predicate->int item)))))
    (:group-by query) (update :group-by (partial mapv predicate->int))
    (:order-by query) (update :order-by (partial mapv (fn [item]
                                                        (if (vector? item)
                                                          (update item 0 predicate->int)
                                                          item))))))

;; Os parâmetros `?` do Firebird 2.5 não têm tipo inferido em vários contextos (SELECT, CASE, COALESCE) e um
;; GROUP BY com parâmetros não casa com a expressão do SELECT. Por isso as consultas do construtor visual são
;; geradas com valores inline (strings escapadas pelo HoneySQL).
(defmethod sql.qp/format-honeysql :firebird
  [driver honeysql-form]
  (binding [driver/*compile-with-inline-parameters* true]
    ((get-method sql.qp/format-honeysql :sql)
     driver
     (walk/postwalk #(cond-> % (and (map? %) (or (:select %) (:group-by %))) fix-predicate-columns)
                    honeysql-form))))

;;; LIMIT / paginação -> ROWS m TO n (vai depois do ORDER BY)

(defn- format-rows [_clause [from to]]
  [(if to
     (format "ROWS %d TO %d" (long from) (long to))
     (format "ROWS %d" (long from)))])

(sql/register-clause! ::rows #'format-rows :limit)

(defmethod sql.qp/apply-top-level-clause [:firebird :limit]
  [_driver _clause honeysql-form {value :limit}]
  (assoc honeysql-form ::rows [value]))

(defmethod sql.qp/apply-top-level-clause [:firebird :page]
  [_driver _clause honeysql-form {{:keys [items page]} :page}]
  (let [offset (* items (dec page))]
    (assoc honeysql-form ::rows [(inc offset) (+ offset items)])))

;;; Booleanos: Firebird 2.5 não tem TRUE/FALSE

(defmethod sql.qp/->honeysql [:firebird Boolean]
  [_driver bool]
  [:inline (if bool 1 0)])

(defmethod sql.qp/inline-value [:firebird Boolean]
  [_driver bool]
  (if bool "1" "0"))

;; Um filtro que é só um valor booleano (ex.: expressão personalizada `True`) vira `WHERE 1`, que o Firebird 2.5
;; rejeita: transforma em comparação (`WHERE 1 = 1`).
(defn- boolean-literal->comparison [hsql]
  (let [x (h2x/unwrap-typed-honeysql-form hsql)]
    (if (and (vector? x) (= :inline (first x)) (#{0 1} (second x)))
      [:= x [:inline 1]]
      hsql)))

(defmethod sql.qp/apply-top-level-clause [:firebird :filters]
  [driver _clause honeysql-form {:keys [filters]}]
  (let [predicates (mapv #(boolean-literal->comparison (sql.qp/->honeysql driver %)) filters)]
    (sql.helpers/where honeysql-form (if (= 1 (count predicates))
                                       (first predicates)
                                       (into [:and] predicates)))))

;;; Literais de data/hora: Firebird 2.5 não tem tipos com fuso horário, usa a hora local do valor

(defmethod sql.qp/inline-value [:firebird OffsetDateTime]
  [driver t]
  (sql.qp/inline-value driver (t/local-date-time t)))

(defmethod sql.qp/inline-value [:firebird ZonedDateTime]
  [driver t]
  (sql.qp/inline-value driver (t/local-date-time t)))

(defmethod sql.qp/inline-value [:firebird OffsetTime]
  [driver t]
  (sql.qp/inline-value driver (t/local-time t)))

;; Parâmetros de consultas nativas ({{variavel}}) continuam usando `?`
(defmethod sql-jdbc.execute/set-parameter [:firebird OffsetDateTime]
  [driver ps i t]
  (sql-jdbc.execute/set-parameter driver ps i (t/local-date-time t)))

(defmethod sql-jdbc.execute/set-parameter [:firebird ZonedDateTime]
  [driver ps i t]
  (sql-jdbc.execute/set-parameter driver ps i (t/local-date-time t)))

(defmethod sql-jdbc.execute/set-parameter [:firebird OffsetTime]
  [driver ps i t]
  (sql-jdbc.execute/set-parameter driver ps i (t/local-time t)))

;;; Tipos de CAST

(defmethod sql.qp/float-dbtype :firebird
  [_driver]
  "DOUBLE PRECISION")

(defmethod sql.qp/->honeysql [:firebird ::sql.qp/cast-to-text]
  [driver [_ _opts expr]]
  (h2x/cast "VARCHAR(1024)" (sql.qp/->honeysql driver expr)))

;;; Funções de texto

(defn- format-nested [expr]
  (sql/format-expr expr {:nested true}))

(defn- format-concat [_fn args]
  (let [parts (map format-nested args)]
    (into [(str "(" (str/join " || " (map first parts)) ")")]
          (mapcat rest)
          parts)))

(sql/register-fn! ::concat #'format-concat)

(defn- format-substring [_fn [expr start length]]
  (let [[expr-sql & expr-args]     (format-nested expr)
        [start-sql & start-args]   (format-nested start)
        [length-sql & length-args] (when length (format-nested length))]
    (into [(if length
             (format "SUBSTRING(%s FROM %s FOR %s)" expr-sql start-sql length-sql)
             (format "SUBSTRING(%s FROM %s)" expr-sql start-sql))]
          cat
          [expr-args start-args length-args])))

(sql/register-fn! ::substring #'format-substring)

(defn- format-trim-side [side]
  (fn [_fn [expr]]
    (let [[expr-sql & expr-args] (format-nested expr)]
      (into [(format "TRIM(%s FROM %s)" side expr-sql)] expr-args))))

(sql/register-fn! ::ltrim (format-trim-side "LEADING"))
(sql/register-fn! ::rtrim (format-trim-side "TRAILING"))

(defmethod sql.qp/->honeysql [:firebird :concat]
  [driver [_ _opts & args]]
  (into [::concat] (map (partial sql.qp/->honeysql driver)) args))

(defmethod sql.qp/->honeysql [:firebird :substring]
  [driver [_ _opts arg start length]]
  (cond-> [::substring (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver start)]
    length (conj (sql.qp/->honeysql driver length))))

(defmethod sql.qp/->honeysql [:firebird :length]
  [driver [_ _opts arg]]
  [:char_length (sql.qp/->honeysql driver arg)])

(defmethod sql.qp/->honeysql [:firebird :ltrim]
  [driver [_ _opts arg]]
  [::ltrim (sql.qp/->honeysql driver arg)])

(defmethod sql.qp/->honeysql [:firebird :rtrim]
  [driver [_ _opts arg]]
  [::rtrim (sql.qp/->honeysql driver arg)])

;; Literais de texto são CHAR no Firebird: num CASE 'alto'/'baixo' o resultado vira CHAR(5) e 'alto' volta com espaço
;; no fim. Convertendo os literais de resultado para VARCHAR o CASE passa a devolver VARCHAR, sem preenchimento.
(defn- string-literal [x]
  (let [x (h2x/unwrap-typed-honeysql-form x)]
    (when (string? x) x)))

(defmethod sql.qp/->honeysql [:firebird :case]
  [driver clause]
  ;; forma compilada: [:case cond1 res1 cond2 res2 ... :else default] -> resultados nas posições ímpares
  (let [[tag & args] ((get-method sql.qp/->honeysql [:sql-mbql5 :case]) driver clause)]
    (into [tag]
          (map-indexed (fn [i x]
                         (if-let [s (and (odd? i) (string-literal x))]
                           (h2x/cast (format "VARCHAR(%d)" (max 1 (count s))) x)
                           x)))
          args)))

;;; Funções matemáticas

(defmethod sql.qp/->honeysql [:firebird :log]
  [driver [_ _opts arg]]
  [:log10 (sql.qp/->honeysql driver arg)])

;;; Datas

(defn- extract [unit expr]
  [::h2x/extract unit expr])

(defn- ->date [expr]
  (h2x/->date expr))

(defn- ->timestamp [expr]
  (h2x/cast-unless-type-in "timestamp" #{"timestamp"} expr))

(defn- dateadd [unit amount expr]
  [:dateadd [:raw unit] amount expr])

(defn- with-type [expr db-type]
  (h2x/with-database-type-info expr db-type))

(defn- trunc-day->timestamp
  "Meia-noite do dia de `expr`, como TIMESTAMP."
  [expr]
  (h2x/cast :timestamp (->date expr)))

(defn- trunc-month [expr]
  (let [d (->date expr)]
    (with-type (dateadd "DAY" (h2x/- [:inline 1] (extract :day d)) d) "date")))

(defn- trunc-week
  "Domingo da semana de `expr` (EXTRACT(WEEKDAY) = 0 no domingo)."
  [expr]
  (let [d (->date expr)]
    (with-type (dateadd "DAY" (h2x/- [:inline 0] (extract :weekday d)) d) "date")))

(defn- firebird-mod [x y]
  [:mod x y])

(defmethod sql.qp/date [:firebird :default] [_driver _unit expr] expr)

(defmethod sql.qp/date [:firebird :second-of-minute]
  [_driver _unit expr]
  (h2x/->integer [:floor (extract :second (->timestamp expr))]))

(defmethod sql.qp/date [:firebird :minute]
  [_driver _unit expr]
  (let [ts (->timestamp expr)]
    (with-type (dateadd "MINUTE"
                        (h2x/+ (h2x/* (extract :hour ts) [:inline 60]) (extract :minute ts))
                        (trunc-day->timestamp ts))
      "timestamp")))

(defmethod sql.qp/date [:firebird :minute-of-hour]
  [_driver _unit expr]
  (extract :minute (->timestamp expr)))

(defmethod sql.qp/date [:firebird :hour]
  [_driver _unit expr]
  (let [ts (->timestamp expr)]
    (with-type (dateadd "HOUR" (extract :hour ts) (trunc-day->timestamp ts)) "timestamp")))

(defmethod sql.qp/date [:firebird :hour-of-day]
  [_driver _unit expr]
  (extract :hour (->timestamp expr)))

(defmethod sql.qp/date [:firebird :day]
  [_driver _unit expr]
  (->date expr))

(defmethod sql.qp/date [:firebird :day-of-week]
  [driver _unit expr]
  (sql.qp/adjust-day-of-week driver
                             (h2x/+ (extract :weekday (->date expr)) [:inline 1])
                             (driver.common/start-of-week-offset driver)
                             firebird-mod))

(defmethod sql.qp/date [:firebird :day-of-month]
  [_driver _unit expr]
  (extract :day expr))

(defmethod sql.qp/date [:firebird :day-of-year]
  [_driver _unit expr]
  ;; YEARDAY começa em 0 no Firebird
  (h2x/+ (extract :yearday expr) [:inline 1]))

(defmethod sql.qp/date [:firebird :week]
  [driver _unit expr]
  (sql.qp/adjust-start-of-week driver trunc-week expr))

(defmethod sql.qp/date [:firebird :week-of-year-iso]
  [_driver _unit expr]
  (extract :week expr))

(defmethod sql.qp/date [:firebird :month]
  [_driver _unit expr]
  (trunc-month expr))

(defmethod sql.qp/date [:firebird :month-of-year]
  [_driver _unit expr]
  (extract :month expr))

(defmethod sql.qp/date [:firebird :quarter]
  [_driver _unit expr]
  (let [month-start (trunc-month expr)]
    (with-type (dateadd "MONTH"
                        (h2x/- [:inline 0] (firebird-mod (h2x/- (extract :month month-start) [:inline 1]) [:inline 3]))
                        month-start)
      "date")))

(defmethod sql.qp/date [:firebird :quarter-of-year]
  [_driver _unit expr]
  ;; divisão inteira (EXTRACT devolve SMALLINT)
  (h2x/+ (h2x// (h2x/- (extract :month expr) [:inline 1]) [:inline 3]) [:inline 1]))

(defmethod sql.qp/date [:firebird :year]
  [_driver _unit expr]
  (let [d (->date expr)]
    (with-type (dateadd "DAY" (h2x/- [:inline 0] (extract :yearday d)) d) "date")))

(defmethod sql.qp/date [:firebird :year-of-era]
  [_driver _unit expr]
  (extract :year expr))

(def ^:private interval-unit->sql
  {:millisecond "MILLISECOND"
   :second      "SECOND"
   :minute      "MINUTE"
   :hour        "HOUR"
   :day         "DAY"
   :month       "MONTH"
   :year        "YEAR"})

(defmethod sql.qp/add-interval-honeysql-form :firebird
  [driver hsql-form amount unit]
  (cond
    (= unit :week)
    (recur driver hsql-form (* amount 7) :day)

    (= unit :quarter)
    (recur driver hsql-form (* amount 3) :month)

    (and (= unit :second) (number? amount) (not (integer? amount)))
    (recur driver hsql-form (long (* amount 1000)) :millisecond)

    :else
    (let [unit-sql (or (interval-unit->sql (sql.qp/check-interval-unit unit))
                       (throw (ex-info (str "Unidade de intervalo não suportada: " unit) {:unit unit})))
          sub-day? (#{:millisecond :second :minute :hour} unit)
          ;; DATEADD de horas/minutos em uma coluna DATE falha no Firebird: converte para TIMESTAMP antes
          base     (if sub-day?
                     (h2x/cast-unless-type-in "timestamp" #{"timestamp" "time"} hsql-form)
                     hsql-form)
          amount   (if (number? amount) (sql.qp/inline-num amount) amount)]
      (cond-> (dateadd unit-sql amount base)
        (h2x/database-type base) (with-type (h2x/database-type base))))))

(def ^:private unix-epoch
  [:raw "TIMESTAMP '1970-01-01 00:00:00'"])

(defmethod sql.qp/unix-timestamp->honeysql [:firebird :seconds]
  [_driver _unit expr]
  (with-type (dateadd "SECOND" expr unix-epoch) "timestamp"))

(defmethod sql.qp/unix-timestamp->honeysql [:firebird :milliseconds]
  [_driver _unit expr]
  (with-type (dateadd "MILLISECOND" expr unix-epoch) "timestamp"))

(defmethod sql.qp/current-datetime-honeysql-form :firebird
  [_driver]
  (with-type [:raw "CURRENT_TIMESTAMP"] "timestamp"))
