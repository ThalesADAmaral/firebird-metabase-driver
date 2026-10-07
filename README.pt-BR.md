# Metabase Firebird 2.5.x Driver

🇺🇸 [Read in English](README.md)

Plugin de driver do [Metabase](https://www.metabase.com/) para **Firebird 2.5** (também funciona com Firebird 3, 4 e
5), feito para as versões atuais do Metabase.

O [evosec/metabase-firebird-driver](https://github.com/evosec/metabase-firebird-driver) original parou de funcionar
por volta do Metabase 0.48, quando o Metabase abandonou o HoneySQL 1, e o repositório foi arquivado em 2025. Este
driver foi reescrito do zero sobre a API atual de drivers (HoneySQL 2, compilação MBQL 5). O Firebird 2.5 é o alvo
principal, então o SQL gerado evita recursos que só existem a partir do Firebird 3.

## Compatibilidade

| Metabase | Firebird | Situação |
|---|---|---|
| v0.63.19.1 | 2.5.9 (dialeto 3) | ✅ testado, 48/48 consultas automatizadas OK |
| v0.61 – v0.62 | — | deve funcionar (a compilação MBQL 5 surgiu na 0.61), não testado |
| ≤ v0.60 | — | ❌ não suportado |

O plugin já inclui o **Jaybird 5.0.13** (build `java11`), a última linha do Jaybird com suporte oficial ao Firebird
2.5.

> O Metabase não tem uma API pública estável para drivers, então uma versão futura pode quebrar qualquer driver de
> terceiros, inclusive este. Veja [Acompanhando as versões do Metabase](#acompanhando-as-versões-do-metabase).

## Instalação

O Metabase carrega, ao iniciar, todo `.jar` que estiver na pasta de plugins. Na imagem Docker oficial essa pasta é
**`/plugins`**, dentro do container. Instalar o driver se resume a três passos:

1. Colocar o `firebird.metabase-driver.jar` numa pasta do host.
2. Montar essa pasta em `/plugins` no container do Metabase.
3. Reiniciar o Metabase.

> **Remova antes qualquer driver Firebird antigo** (por exemplo o `firebird.metabase-driver_jaybird-3.jar` da evosec).
> Os dois registram o driver `firebird`; com o antigo carregado aparece o erro
> `No method in multimethod 'connection-details->spec' for dispatch value: :firebird`.

### Passo 1 – Baixar o jar no servidor

Rode estes comandos no servidor onde está o Docker:

```bash
sudo mkdir -p /opt/metabase/plugins
sudo curl -fL -o /opt/metabase/plugins/firebird.metabase-driver.jar \
  https://github.com/ThalesADAmaral/firebird-metabase-driver/releases/latest/download/firebird.metabase-driver.jar
ls -l /opt/metabase/plugins
```

Você também pode [gerar o jar](#build) e copiá-lo com `scp`:

```bash
scp dist/firebird.metabase-driver.jar root@seu-servidor:/opt/metabase/plugins/
```

### Opção A – `docker run`

```bash
docker run -d --name metabase -p 3000:3000 \
  -v /opt/metabase/plugins:/plugins \
  metabase/metabase:v0.63.19.1
```

Não dá para adicionar um volume a um container que já existe, então o container precisa ser recriado com `-v`.

> ⚠️ **Antes de recriar o container, confira onde o Metabase guarda os próprios dados** (dashboards, perguntas,
> usuários). Com `MB_DB_TYPE=postgres` ou `mysql`, os dados ficam nesse banco e recriar o container é seguro. Com o
> banco H2 embutido (o padrão), os dados ficam dentro do container, a menos que o arquivo H2 esteja num volume. Nesse
> caso, faça backup ou migre para Postgres antes, senão você perde os dashboards.

### Opção B – Docker Compose

Adicione o volume no serviço do Metabase e rode `docker compose up -d`:

```yaml
services:
  metabase:
    image: metabase/metabase:v0.63.19.1
    volumes:
      - /opt/metabase/plugins:/plugins
    # ...o resto da sua configuração (MB_DB_TYPE, portas etc.)
```

### Opção C – Coolify

O **serviço Metabase** de um clique do Coolify é uma stack Docker Compose. Ele guarda os dados do Metabase num
Postgres da mesma stack, então fazer redeploy é seguro. O template **não** monta `/plugins`, por isso é preciso
adicionar o volume no compose.

1. **No servidor (SSH):** baixe o jar como no [Passo 1](#passo-1--baixar-o-jar-no-servidor). Qualquer caminho
   absoluto serve; este guia usa `/opt/metabase/plugins`.
2. **No Coolify:** abra o serviço do Metabase e clique em **Edit Compose File**.
3. No serviço `metabase`, acrescente o volume em `volumes:`, mantendo a linha que já existe:
   ```yaml
   services:
     metabase:
       image: metabase/metabase:v0.63.19.1   # recomendado: fixar uma versão testada em vez de :latest
       volumes:
         - /dev/urandom:/dev/random:ro
         - /opt/metabase/plugins:/plugins
   ```
   Use caminho **absoluto** no host. Um caminho relativo como `./plugins` aponta para a pasta interna do serviço no
   Coolify, não para a pasta onde você colocou o jar.
4. Clique em **Save** e depois em **Restart** (ou **Redeploy**) no serviço.
5. Abra os **Logs** do serviço e procure por `firebird` (veja [Conferir se carregou](#conferir-se-carregou)).

Se o Metabase foi criado no Coolify como **Application** (a partir de uma imagem Docker) e não como Service, adicione
o volume pela interface: **Configuration → Persistent Storage → Add → Directory Mount**, com origem
`/opt/metabase/plugins` e destino `/plugins`. Depois faça o redeploy.

### Teste rápido sem volume (não persistente)

Dá para copiar o jar para dentro do container em execução e testar o driver. A cópia some quando o container é
recriado, por exemplo no próximo redeploy do Coolify ou na atualização do Metabase:

```bash
docker cp firebird.metabase-driver.jar <container>:/plugins/
docker restart <container>
```

### Rodando o JAR direto (sem Docker)

Coloque o jar na pasta `plugins/` ao lado do `metabase.jar`, ou aponte `MB_PLUGINS_DIR` para a pasta onde ele está.

### Conferir se carregou

```bash
docker ps --format '{{.Names}}\t{{.Image}}'                 # descobre o nome do container
docker exec <container> ls -l /plugins                       # o jar precisa aparecer aqui
docker logs <container> 2>&1 | grep -i firebird
```

Linhas esperadas no log:

```
Registering lazy loading driver :firebird...
Registered driver :firebird (parents: [:sql-jdbc])
```

A segunda linha vem do manifesto do plugin. Na primeira conexão a um banco, o driver em si carrega e registra
`Registered driver :firebird (parents: [:sql-jdbc :sql-mbql5])`.

Depois disso, **Firebird** aparece em **Admin → Databases → Add database**.

### Atualizando o driver

Substitua o jar na pasta do host (mesmo nome de arquivo) e reinicie o container (ou o serviço no Coolify). Não há
outro passo.

### Solução de problemas

| Sintoma | Causa / solução |
|---|---|
| `No method in multimethod 'connection-details->spec' for dispatch value: :firebird` | Ainda há um driver antigo (evosec) em `/plugins`. Remova e reinicie. |
| Duas opções de Firebird na lista de bancos ("FirebirdSQL" e "Firebird") | Mesma causa: o driver antigo ainda está carregado. |
| Firebird não aparece na lista de bancos | O jar não está em `/plugins`. Confira o volume com `docker exec <container> ls -l /plugins` e o log. |
| Timeout ou "connection refused" ao salvar | Rede. No host do Docker, rode `nc -zv <host-firebird> <porta>`. Um Firebird na rede local não é alcançável de um VPS na nuvem; use VPN (Tailscale, WireGuard…) ou o túnel SSH. |
| Acentos aparecem errados | Troque o **Charset da conexão** (`WIN1252`, `ISO8859_1`, `UTF8`…). |
| Firebird numa porta diferente da padrão | Informe a porta em **Port**. O Firebird 2.5 não precisa de mudanças no `firebird.conf`: ele só usa autenticação legada e não tem criptografia de rede. |

## Conectando

No Metabase: **Admin → Databases → Add database → Firebird**.

| Campo | Exemplo | Observação |
|---|---|---|
| Host | `10.0.0.5` | Precisa ser alcançável de onde o Metabase roda (de dentro do container, no Docker) |
| Port | `3050` | |
| Caminho ou alias do banco | `C:/Dados/BANCO.FDB`, `/var/lib/firebird/banco.fdb` ou `MEUALIAS` | Caminho **no servidor Firebird**, ou alias do `aliases.conf` |
| Username / Password | | Prefira um usuário só de leitura |
| Charset da conexão | `WIN1252` | Encoding do Jaybird: `WIN1252`, `ISO8859_1`, `UTF8`, `NONE`, … |
| SSH tunnel | | Útil quando o Firebird está numa rede privada |
| Additional JDBC options | `blobBufferSize=16384` | Repassado ao Jaybird |

Observações:

- **Charset:** se aparecerem acentos errados, troque o valor. `WIN1252` é o padrão por ser o mais comum em bancos
  brasileiros.
- **Segurança:** o protocolo do Firebird 2.5 não é criptografado. Não exponha a porta 3050 na internet; use VPN ou o
  túnel SSH.
- **Usuário só de leitura:** o Metabase só lê, mas o SQL nativo permite digitar qualquer comando. Conecte com um
  usuário que tenha apenas permissão de `SELECT`.

## Como funciona

O Firebird 2.5 não tem vários recursos que o gerador de SQL genérico do Metabase pressupõe. O driver traduz:

| O Metabase gera | O driver emite para o Firebird 2.5 |
|---|---|
| `LIMIT n` / `OFFSET m` | `ROWS n` / `ROWS m TO n` (inclusive em subconsultas) |
| `TRUE` / `FALSE` | `1` / `0` (o 2.5 não tem BOOLEAN) |
| `date_trunc(...)` | `DATEADD` + `EXTRACT` para minuto, hora, dia, semana, mês, trimestre, ano |
| início da semana | respeita a configuração *start of week* do Metabase |
| `concat(a, b)` | `a \|\| b` |
| `substring(x, i, n)` | `SUBSTRING(x FROM i FOR n)` |
| `length`, `ltrim`, `rtrim`, `log` | `CHAR_LENGTH`, `TRIM(LEADING/TRAILING FROM …)`, `LOG10` |
| aliases longos gerados | truncados em 31 bytes (limite de identificador do Firebird 2.5) |
| textos fixos dentro de `CASE` | convertidos para `VARCHAR`, então o resultado volta sem espaços de preenchimento |
| expressões booleanas como coluna (`SELECT a > b`) | `CASE WHEN a > b THEN 1 ELSE 0 END` (o 2.5 só aceita comparações em WHERE/CASE) |
| parâmetros `?` no construtor visual | valores inline (escapados). O Firebird 2.5 não infere o tipo de `?` em SELECT/CASE/COALESCE, e expressões com parâmetro não batem com o GROUP BY correspondente |

Consultas em SQL nativo continuam usando parâmetros JDBC de verdade para as `{{variáveis}}`.

### Limitações

- Ficam desligados os recursos que o Firebird 2.5 não tem: funções de janela, desvio padrão e variância, percentil e
  mediana, regex. Somas e contagens acumuladas continuam funcionando, porque o próprio Metabase calcula.
- Só bancos em **dialeto 3** são suportados, porque o Metabase sempre coloca os identificadores entre aspas. O dialeto
  3 é o padrão desde o Firebird 1.5.
- O Firebird não tem schemas, então todas as tabelas aparecem no nível principal.

## Build

O plugin é distribuído como **código-fonte** Clojure, sem classes compiladas (AOT). O Metabase compila o driver ao
carregá-lo, então o build não precisa de Java nem de Clojure: ele só empacota o código-fonte, o manifesto do plugin e
o Jaybird num zip.

**Windows (PowerShell):**

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

O script baixa o Jaybird do Maven Central para `lib/` (só na primeira vez) e gera
`dist/firebird.metabase-driver.jar`.

### Estrutura

```
src/metabase/driver/firebird.clj   o driver
resources/metabase-plugin.yaml     manifesto do plugin (nome, formulário de conexão)
build.ps1                          gera dist/firebird.metabase-driver.jar
testes/                            script do banco de teste + bateria de consultas
LICENSE                            MIT
```

## Testes

A bateria em `testes/` roda consultas reais pela API HTTP do Metabase contra um banco Firebird de teste. Ela cobre o
sync, todos os agrupamentos de data, filtros de data relativos, joins, paginação, subconsultas, expressões,
agregações, faixas de valores (binning) e SQL nativo com variáveis e filtros de campo.

1. Suba um servidor Firebird 2.5 descartável. O kit zip oficial funciona sem instalar: `fbserver.exe -a -p 3051`.
2. Crie o banco de teste: `isql -i testes/criar-banco-teste.sql`. Antes, ajuste o caminho nas primeiras linhas.
3. Suba o Metabase com `MB_PLUGINS_DIR` apontando para uma pasta que contenha o jar gerado, e cadastre o banco de
   teste.
4. Grave o session id de um admin em `testes/sid.txt`.
5. Rode as baterias:
   ```bash
   python testes/tests.py
   python testes/tests2.py
   ```
   `MB_TEST_DB` define o id do banco no Metabase (padrão `2`).

> ⚠️ Nunca aponte os testes para um banco de produção.

## Acompanhando as versões do Metabase

Drivers de terceiros rodam sobre as partes **internas** do Metabase, que mudam entre versões. Este driver reduz o
risco:

- **Sem compilação AOT.** Drivers compilados quebram com qualquer mudança nas classes internas. Este é recompilado a
  partir do código-fonte por cada versão do Metabase.
- **Superfície pequena.** Ele só sobrescreve multimétodos que os próprios drivers embutidos do Metabase (H2, SQL
  Server, Vertica, SQLite) também implementam, então o time do Metabase mantém esses pontos funcionando e documenta as
  mudanças.
- **Já está no MBQL 5.** O Metabase está migrando a compilação de SQL para o MBQL 5 e exige que todos os drivers
  migrem até a v64. Este driver já herda de `:sql-mbql5`, como o driver H2 oficial do Metabase, então está à frente
  desse prazo.
- **Changelog oficial.** O Metabase registra toda mudança na API de drivers em
  [`docs/developers-guide/driver-changelog.md`](https://github.com/metabase/metabase/blob/master/docs/developers-guide/driver-changelog.md).
  Leia antes de atualizar.

Recomendações:

- Fixe a imagem do Metabase numa versão testada (`metabase/metabase:v0.63.19.1`, não `:latest`).
- Antes de atualizar, rode a bateria de testes contra a versão nova.

## Contribuindo

Issues e PRs são bem-vindos, principalmente relatos de versões do Metabase e do Firebird que você testou. Ao reportar
erro numa consulta, inclua o SQL gerado (**View the SQL** no editor) e o erro do log do Metabase.

## Créditos

- Desenvolvido por **ThalesADAmaral** em colaboração com o **Claude** ([Anthropic](https://www.anthropic.com/)).
- Inspirado no [evosec/metabase-firebird-driver](https://github.com/evosec/metabase-firebird-driver).
- Usa o [Jaybird](https://github.com/FirebirdSQL/jaybird), driver JDBC do Firebird, licenciado sob LGPL-2.1.

## Licença

[MIT](LICENSE). Qualquer pessoa pode usar, modificar e redistribuir este projeto, inclusive comercialmente, desde que
mantenha o aviso de copyright e créditos do [LICENSE](LICENSE). O Jaybird embutido mantém sua própria licença
LGPL-2.1.
