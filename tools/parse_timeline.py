import struct,sys,datetime
f=open(sys.argv[1],'rb').read()
assert f[:8]==b'btsnoop\0',f[:8]
pos=16; rows=[]
while pos+24<=len(f):
    ol,il,fl,dr,ts=struct.unpack('>IIIIq',f[pos:pos+24]); pos+=24
    d=f[pos:pos+il]; pos+=il
    if not d or d[0]!=1: continue           # HCI command only
    op=d[1]|(d[2]<<8)
    if op not in (0x2008,0x2037): continue
    p=d[4:]
    if op==0x2037: p=p[4:]                    # handle,op,frag,len
    else: p=p[1:]                             # adv len
    i=0
    while i<len(p):
        L=p[i]; 
        if L==0: break
        t=p[i+1]; v=p[i+2:i+1+L]
        if t==0x07 and len(v)>=16:
            u=v[:16][::-1]                    # AD is little-endian → reverse to spec byte order
            if u[0]==0x71 and u[2]==0x17:
                t_us=ts-0x00dcddb30f2f8000    # btsnoop epoch → unix
                rows.append((t_us/1e6,u.hex()))
        i+=1+L
print("frames:",len(rows))
last=None
for t,u in rows:
    if u[8:]==last: continue                  # skip same command (ignore nonce byte 3)
    last=u[8:]
    b=bytes.fromhex(u); typ=b[4]; 
    if typ==0x51: desc=f"吮吸 {(b[13]-50)*2}"
    elif typ==0x01: desc="吮吸关"
    elif typ==0x52: desc=f"入体 {102-b[11]}"
    elif typ==0x02: desc="总关"
    else: desc=f"未知类型{typ:02X}"
    print(f"{datetime.datetime.fromtimestamp(t).strftime('%H:%M:%S.%f')[:-3]}  {desc:10s} {u}")
