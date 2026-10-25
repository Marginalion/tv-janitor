"""Turn CleanYourTV examples/*/debloat.txt into compact tables for the Cleaner app."""
import json, os, re, sys, collections
root = sys.argv[1]; out = sys.argv[2]
PKG = r'[a-z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+'
off = collections.defaultdict(list)    # pkg -> [(example, note)]
keep = collections.defaultdict(list)
devices = []
for ex in sorted(os.listdir(root)):
    f = os.path.join(root, ex, 'debloat.txt')
    if not os.path.isfile(f): continue
    text = open(f, encoding='utf-8', errors='replace').read()
    tv = ex
    try: tv = json.load(open(os.path.join(root, ex, 'tv.json'), encoding='utf-8')).get('tv', ex)
    except Exception: pass
    ids = set()
    for m in re.finditer(r'ro\.product\.(?:model|device|name)\s*[=:]\s*([^\s,;)]+)', text): ids.add(m.group(1).strip('`"\''))
    for m in re.finditer(r'[Ff]ingerprint:?\s*([^\s]+)', text):
        parts = m.group(1).split('/')
        if len(parts) > 2: ids.add(parts[1]); ids.add(parts[2].split(':')[0])
    ex_off, ex_keep = [], []
    in_dnd = False
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip(): in_dnd = False; continue
        if not line.lstrip().startswith('#'):
            m = re.match(r'\s*(' + PKG + r')\s*(?:#\s*(.*))?$', line)
            if m: off[m.group(1)].append((ex, (m.group(2) or '').strip())); ex_off.append(m.group(1))
            in_dnd = False
            continue
        body = line.lstrip()[1:]
        if re.search(r'do not disable|don\'t disable|keep enabled|never disable', body, re.I):
            in_dnd = True
            for p in re.findall(r'`?(' + PKG + r')`?', body):
                if p.count('.') >= 2 or p.startswith(('com.', 'org.', 'ru.', 'tv.', 'mitv.')):
                    if not p.endswith(('.md', '.txt', '.sh', '.py', '.json', '.xml', '.java')):
                        keep[p].append((ex, re.sub(r'\s+', ' ', body).strip()[:200])); ex_keep.append(p)
            continue
        if in_dnd:
            m = re.match(r'\s{1,6}(' + PKG + r')\s{2,}(.*)$', body)
            if m and not m.group(1).endswith(('.md', '.txt')):
                keep[m.group(1)].append((ex, m.group(2).strip()[:200])); ex_keep.append(m.group(1))
            elif not body.startswith('    ') and body.strip() and not re.match(r'\s', body):
                in_dnd = False
    devices.append((ex, tv, sorted(ids), ex_off, sorted(set(ex_keep))))
def best(notes):
    ns = [n for _, n in notes if n]
    return min(ns, key=lambda n: (len(n) < 15, abs(len(n) - 90))) if ns else ''
with open(os.path.join(out, 'community.tsv'), 'w', encoding='utf-8') as o:
    for p in sorted(set(off) | set(keep)):
        o.write('\t'.join([p, str(len({e for e, _ in off[p]})), str(len({e for e, _ in keep[p]})),
                           best(off[p]).replace('\t', ' '), best(keep[p]).replace('\t', ' ')]) + '\n')
with open(os.path.join(out, 'devices.tsv'), 'w', encoding='utf-8') as o:
    for ex, tv, ids, eo, ek in devices:
        o.write('\t'.join([ex, tv.replace('\t', ' '), ','.join(ids), ','.join(eo), ','.join(ek)]) + '\n')
print(len(devices), 'TVs;', len(off), 'packages disabled somewhere;', len(keep), 'marked keep')
