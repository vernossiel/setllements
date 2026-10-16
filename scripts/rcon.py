import socket, struct, sys
def pkt(i, t, body):
    b = body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<iii', len(b) + 8, i, t) + b
def recv(s):
    n = struct.unpack('<i', s.recv(4))[0]
    d = b''
    while len(d) < n: d += s.recv(n - len(d))
    return d[8:-2].decode('utf-8', 'replace')
s = socket.create_connection(('127.0.0.1', 25575), timeout=60)
s.sendall(pkt(1, 3, 'test')); recv(s)
for cmd in sys.argv[1:]:
    s.sendall(pkt(2, 2, cmd)); print('>', cmd); print(recv(s))
