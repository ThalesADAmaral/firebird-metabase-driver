exec(open(__file__.replace('tests2.py', 'runq.py'), encoding='utf-8').read())

C, P = T['CLIENTES'], T['PEDIDOS']

run('case com literais (sem espaco)', {'source-table': P, 'limit': 3, 'expressions': {
    'caso': ['case', [[['>', f('PEDIDOS.VALOR'), 100], 'alto'], [['>', f('PEDIDOS.VALOR'), 50], 'medio']], {'default': 'baixo'}],
    'num': ['case', [[['>', f('PEDIDOS.VALOR'), 100], 1]], {'default': 0}]},
    'fields': [f('PEDIDOS.VALOR'), ['expression', 'caso'], ['expression', 'num']]})
run('breakout por case', {'source-table': P, 'aggregation': [['count']], 'expressions': {
    'faixa': ['case', [[['>', f('PEDIDOS.VALOR'), 100], 'alto']], {'default': 'baixo'}]},
    'breakout': [['expression', 'faixa']]})
run('filtro = em expressao case', {'source-table': P, 'aggregation': [['count']], 'expressions': {
    'faixa': ['case', [[['>', f('PEDIDOS.VALOR'), 100], 'alto']], {'default': 'baixo'}]},
    'filter': ['=', ['expression', 'faixa'], 'alto']})

run('filtro por expressao booleana', {'source-table': P, 'aggregation': [['count']], 'expressions': {
    'caro': ['>', f('PEDIDOS.VALOR'), 100]},
    'filter': ['expression', 'caro']})
run('expressao booleana como coluna', {'source-table': P, 'limit': 2, 'expressions': {
    'caro': ['>', f('PEDIDOS.VALOR'), 100]},
    'fields': [f('PEDIDOS.VALOR'), ['expression', 'caro']]})
run('breakout por expressao booleana', {'source-table': P, 'aggregation': [['count']], 'expressions': {
    'caro': ['>', f('PEDIDOS.VALOR'), 100]},
    'breakout': [['expression', 'caro']]})
run('order-by por expressao booleana', {'source-table': P, 'limit': 3, 'expressions': {
    'caro': ['and', ['>', f('PEDIDOS.VALOR'), 100], ['=', f('PEDIDOS.STATUS'), 'A']]},
    'fields': [f('PEDIDOS.ID'), ['expression', 'caro']], 'order-by': [['desc', ['expression', 'caro']], ['asc', f('PEDIDOS.ID')]]})

# inicio da semana = segunda
import urllib.request as _u
_r = _u.Request(BASE + '/api/setting/start-of-week', data=json.dumps({'value': 'monday'}).encode(), method='PUT',
                headers={'Content-Type': 'application/json', 'X-Metabase-Session': SID})
_u.urlopen(_r).read()
run('semana iniciando segunda', {'source-table': P, 'aggregation': [['count']],
                                 'breakout': [f('PEDIDOS.EMISSAO', **{'temporal-unit': 'week'})]})
run('day-of-week com segunda=1', {'source-table': P, 'aggregation': [['count']],
                                  'breakout': [f('PEDIDOS.EMISSAO', **{'temporal-unit': 'day-of-week'})]})
_r = _u.Request(BASE + '/api/setting/start-of-week', data=json.dumps({'value': 'sunday'}).encode(), method='PUT',
                headers={'Content-Type': 'application/json', 'X-Metabase-Session': SID})
_u.urlopen(_r).read()

run('nativo com field filter (dimension) de data', {
    'query': 'SELECT ID, EMISSAO FROM PEDIDOS WHERE {{periodo}} ORDER BY ID',
    'template-tags': {'periodo': {'id': 'b1', 'name': 'periodo', 'display-name': 'Periodo', 'type': 'dimension',
                                  'dimension': ['field', F['PEDIDOS.EMISSAO'], None], 'widget-type': 'date/range'}}},
    native=True,
    params=[{'type': 'date/range', 'target': ['dimension', ['template-tag', 'periodo']], 'value': '2025-02-01~2025-04-30'}])
run('nativo com field filter de texto (lista)', {
    'query': 'SELECT ID, NOME FROM CLIENTES WHERE {{cidade}} ORDER BY ID',
    'template-tags': {'cidade': {'id': 'b2', 'name': 'cidade', 'display-name': 'Cidade', 'type': 'dimension',
                                 'dimension': ['field', F['CLIENTES.CIDADE'], None], 'widget-type': 'string/='}}},
    native=True,
    params=[{'type': 'string/=', 'target': ['dimension', ['template-tag', 'cidade']], 'value': ['São Paulo', 'Curitiba']}])

summary()
