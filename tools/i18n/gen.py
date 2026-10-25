import json, sys, os, re
D = os.path.dirname(os.path.abspath(__file__))
keys = list(json.load(open(os.path.join(D, 'src', 'keys.json'))).keys())
core = json.load(open(os.path.join(D, 'src', 'core.json')))
idx = {s: i for i, s in enumerate(core)}
out = sys.argv[1]
for f in sorted(os.listdir(D)):
    if not f.endswith('.json'): continue
    lang = f[:-5]; tr = json.load(open(os.path.join(D, f)))
    assert len(tr) == len(core), (lang, len(tr))
    lines = ['# TV Janitor translation: ' + lang + '. Source (Russian) TAB translation. Machine-assisted; fixes welcome.']
    for k in keys:
        s = k.strip()
        if not s: continue
        lead = k[:len(k) - len(k.lstrip())]; trail = k[len(k.rstrip()):]
        v = lead + tr[idx[s]] + trail
        esc = lambda x: x.replace('\t', '\\t').replace('\n', '\\n')
        lines.append(esc(k) + '\t' + esc(v))
    os.makedirs(out, exist_ok=True)
    open(os.path.join(out, lang + '.tsv'), 'w').write('\n'.join(lines) + '\n')
    print(lang, len(lines) - 1)
