import sys, os, time
out = sys.argv[1]; files = sys.argv[2:]
t = int(time.time())
with open(out, 'wb') as f:
    f.write(b'!<arch>\n')
    for p in files:
        d = open(p, 'rb').read()
        h = '%-16s%-12d%-6d%-6d%-8s%-10d`\n' % (os.path.basename(p), t, 0, 0, '100644', len(d))
        f.write(h.encode()); f.write(d)
        if len(d) % 2: f.write(b'\n')
