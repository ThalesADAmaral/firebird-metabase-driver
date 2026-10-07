exec(open(__file__.replace('tests.py', 'runq.py'), encoding='utf-8').read())

C, P = T['CLIENTES'], T['PEDIDOS']

run('linhas CLIENTES limit 3 (acentos)', {'source-table': C, 'limit': 3})

for unit in ['minute', 'hour', 'day', 'week', 'month', 'quarter', 'year', 'minute-of-hour', 'hour-of-day',
             'day-of-week', 'day-of-month', 'day-of-year', 'week-of-year', 'month-of-year', 'quarter-of-year',
             'year-of-era']:
    run('breakout EMISSAO:' + unit, {'source-table': P, 'aggregation': [['count']],
                                     'breakout': [f('PEDIDOS.EMISSAO', **{'temporal-unit': unit})]})

run('breakout CADASTRO (DATE) week+hour', {'source-table': C, 'aggregation': [['count']],
                                           'breakout': [f('CLIENTES.CADASTRO', **{'temporal-unit': 'week'}),
                                                        f('CLIENTES.CADASTRO', **{'temporal-unit': 'hour'})]})

run('filtro contains case-insensitive', {'source-table': C, 'fields': [f('CLIENTES.NOME')],
                                         'filter': ['contains', f('CLIENTES.NOME'), 'JO', {'case-sensitive': False}]})
run('filtro starts-with + ATIVO=1', {'source-table': C, 'fields': [f('CLIENTES.NOME')],
                                     'filter': ['and', ['starts-with', f('CLIENTES.NOME'), 'Jo'],
                                                ['=', f('CLIENTES.ATIVO'), 1]]})
run('filtro texto com aspa e %', {'source-table': C, 'aggregation': [['count']],
                                  'filter': ['contains', f('CLIENTES.NOME'), "d'a%"]})
run('filtro data between', {'source-table': P, 'fields': [f('PEDIDOS.ID'), f('PEDIDOS.EMISSAO')],
                            'filter': ['between', f('PEDIDOS.EMISSAO'), '2025-02-01', '2025-06-30']})
run('filtro relativo: ultimos 2 anos', {'source-table': P, 'aggregation': [['count']],
                                        'filter': ['time-interval', f('PEDIDOS.EMISSAO'), -2, 'year']})
run('filtro relativo: este ano ou mes passado', {'source-table': P, 'aggregation': [['count']],
                                                 'filter': ['or', ['time-interval', f('PEDIDOS.EMISSAO'), 'current', 'year'],
                                                            ['time-interval', f('PEDIDOS.EMISSAO'), -1, 'month']]})
run('filtro is-null / not-null', {'source-table': C, 'aggregation': [['count']],
                                  'filter': ['and', ['is-null', f('CLIENTES.OBS')], ['not-null', f('CLIENTES.NOME')]]})

run('join FK + sum + order + limit', {'source-table': P, 'aggregation': [['sum', f('PEDIDOS.VALOR')]],
                                      'breakout': [['field', F['CLIENTES.NOME'], {'source-field': F['PEDIDOS.CLIENTE_ID']}]],
                                      'order-by': [['desc', ['aggregation', 0]]], 'limit': 2})
run('paginacao page 2 items 3', {'source-table': P, 'fields': [f('PEDIDOS.ID')],
                                 'order-by': [['asc', f('PEDIDOS.ID')]], 'page': {'page': 2, 'items': 3}})

run('expressoes texto', {'source-table': C, 'limit': 2, 'expressions': {
    'concat': ['concat', f('CLIENTES.NOME'), ' / ', f('CLIENTES.CIDADE')],
    'sub': ['substring', f('CLIENTES.NOME'), 1, 4],
    'len': ['length', f('CLIENTES.NOME')],
    'lt': ['ltrim', f('CLIENTES.CIDADE')], 'rt': ['rtrim', f('CLIENTES.CIDADE')], 'tr': ['trim', f('CLIENTES.CIDADE')],
    'up': ['upper', f('CLIENTES.NOME')], 'rep': ['replace', f('CLIENTES.NOME'), 'a', '@'],
    'txt': ['text', f('CLIENTES.LIMITE')]},
    'fields': [['expression', e] for e in ['concat', 'sub', 'len', 'lt', 'rt', 'tr', 'up', 'rep', 'txt']]})

run('expressoes numero/data/case', {'source-table': P, 'limit': 3, 'expressions': {
    'log': ['log', f('PEDIDOS.VALOR')], 'pow': ['power', f('PEDIDOS.QTD'), 2], 'sqrt': ['sqrt', f('PEDIDOS.VALOR')],
    'round': ['round', f('PEDIDOS.VALOR')], 'exp': ['exp', 1],
    'mais1mes': ['datetime-add', f('PEDIDOS.EMISSAO'), 1, 'month'],
    'mais3h': ['datetime-add', f('PEDIDOS.EMISSAO'), 3, 'hour'],
    'menos1tri': ['datetime-subtract', f('PEDIDOS.EMISSAO'), 1, 'quarter'],
    'caso': ['case', [[['>', f('PEDIDOS.VALOR'), 100], 'alto']], {'default': 'baixo'}],
    'semana': ['get-week', f('PEDIDOS.EMISSAO'), 'iso'], 'dow': ['get-day-of-week', f('PEDIDOS.EMISSAO')],
    'hoje': ['now']},
    'fields': [f('PEDIDOS.ID')] + [['expression', e] for e in
                                   ['log', 'pow', 'sqrt', 'round', 'exp', 'mais1mes', 'mais3h', 'menos1tri', 'caso',
                                    'semana', 'dow', 'hoje']]})

run('agregacoes', {'source-table': P, 'aggregation': [
    ['count'], ['distinct', f('PEDIDOS.CLIENTE_ID')], ['avg', f('PEDIDOS.VALOR')],
    ['count-where', ['=', f('PEDIDOS.STATUS'), 'A']],
    ['sum-where', f('PEDIDOS.VALOR'), ['=', f('PEDIDOS.STATUS'), 'A']],
    ['share', ['=', f('PEDIDOS.STATUS'), 'A']],
    ['min', f('PEDIDOS.EMISSAO')], ['max', f('PEDIDOS.HORA')]]})
run('cum-sum / cum-count por mes', {'source-table': P, 'aggregation': [['cum-sum', f('PEDIDOS.VALOR')], ['cum-count']],
                                    'breakout': [f('PEDIDOS.EMISSAO', **{'temporal-unit': 'month'})]})
run('binning VALOR', {'source-table': P, 'aggregation': [['count']],
                      'breakout': [['field', F['PEDIDOS.VALOR'], {'binning': {'strategy': 'num-bins', 'num-bins': 10}}]]})
run('nested query', {'source-query': {'source-table': P, 'aggregation': [['sum', f('PEDIDOS.VALOR')]],
                                      'breakout': [f('PEDIDOS.CLIENTE_ID')]},
                     'aggregation': [['count']]})
run('nested query + limit interno', {'source-query': {'source-table': P, 'order-by': [['desc', f('PEDIDOS.VALOR')]],
                                                      'limit': 3}, 'limit': 2})
run('view V_PEDIDOS', {'source-table': T['V_PEDIDOS'], 'limit': 2})

run('nativo simples', {'query': 'SELECT FIRST 2 NOME, CADASTRO, OBS FROM CLIENTES ORDER BY ID'}, native=True)
run('nativo com {{data}} e {{nome}}',
    {'query': 'SELECT ID, NOME FROM CLIENTES WHERE CADASTRO >= {{data}} AND NOME CONTAINING {{nome}}',
     'template-tags': {'data': {'id': 'a1', 'name': 'data', 'display-name': 'Data', 'type': 'date'},
                       'nome': {'id': 'a2', 'name': 'nome', 'display-name': 'Nome', 'type': 'text'}}},
    native=True,
    params=[{'type': 'date/single', 'target': ['variable', ['template-tag', 'data']], 'value': '2024-02-01'},
            {'type': 'category', 'target': ['variable', ['template-tag', 'nome']], 'value': 'Ana'}])

summary()
