import json, os, urllib.request

os.chdir(os.path.dirname(os.path.abspath(__file__)))
SID = open('sid.txt').read().strip()
BASE = 'http://localhost:3300'
DB = int(os.environ.get('MB_TEST_DB', '2'))


def call(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data,
                                 headers={'Content-Type': 'application/json', 'X-Metabase-Session': SID})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return json.load(e)
        except Exception:
            return {'error': str(e)}


meta = call('/api/database/%d/metadata' % DB)
F, T = {}, {}
for t in meta['tables']:
    T[t['name']] = t['id']
    for fld in t['fields']:
        F[t['name'] + '.' + fld['name']] = fld['id']


def f(name, **opts):
    return ['field', F[name], opts or None]


RESULTS = []


def run(label, query, native=False, params=None):
    body = ({'database': DB, 'type': 'native', 'native': query} if native
            else {'database': DB, 'type': 'query', 'query': query})
    if params:
        body['parameters'] = params
    r = call('/api/dataset', body)
    ok = r.get('status') == 'completed'
    RESULTS.append((label, ok))
    print('=' * 100)
    print(('OK   ' if ok else 'FAIL ') + label)
    if ok:
        rows = r['data']['rows']
        print('  rows:', rows[:12], '...' if len(rows) > 12 else '')
    else:
        print('  error:', str(r.get('error') or r.get('message') or r)[:700])
    if not native:
        n = call('/api/dataset/native', body)
        print('  SQL:', str(n.get('query') or n)[:1200].replace('\n', ' '))
    return r


def summary():
    print('=' * 100)
    fails = [l for l, ok in RESULTS if not ok]
    print('TOTAL %d, OK %d, FAIL %d' % (len(RESULTS), len(RESULTS) - len(fails), len(fails)))
    for l in fails:
        print('  FAIL:', l)
