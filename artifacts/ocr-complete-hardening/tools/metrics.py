import json,sys
from decimal import Decimal
S=sys.argv[1]
data={d['id']:d for d in json.load(open(f'{S}/bench/text_invoices.json'))}
def metrics(path, extra=None):
    out={o['id']:o for o in json.load(open(path))}
    ex={o['id']:o for o in json.load(open(extra))} if extra else {}
    m=dict(n=len(data),total=0,tax=0,wrongTaxShown=0,catastrophicTaxShown=0,buyer=0,buyerWrongName=0,items=0,itemRows=0,itemRowsTrue=0,itemRowsJunk=0)
    if ex: m.update(gstin=0,wrongAny=0,flaggedWhenWrong=0,readyButWrong=0)
    rows=[]
    for i,d in data.items():
        t=d['truth']; o=out[i]
        tot_ok = (o.get('total')==t['total'] and o['totalFromLabel']) if t['totalReadable'] else (not o['totalFromLabel'])
        m['total']+=tot_ok
        tax=o.get('tax'); taxok = (tax==t['tax']) if t['taxReadable'] else (tax is None)
        m['tax']+=taxok
        if tax is not None and tax!=t['tax']: m['wrongTaxShown']+=1
        if tax is not None and Decimal(tax) >= Decimal(t['total']): m['catastrophicTaxShown']+=1
        bok = (o.get('buyer')==t['buyer'])
        m['buyer']+=bok
        if o.get('buyer') is not None and o.get('buyer')!=t['buyer']: m['buyerWrongName']+=1
        got=[tuple(x) for x in o['items']]; want=[tuple(x) for x in t['items']]
        iok = got==want
        m['items']+=iok
        m['itemRowsTrue']+=sum(1 for x in got if x in want); m['itemRows']+=len(want); m['itemRowsJunk']+=sum(1 for x in got if x not in want)
        st=None
        if ex:
            e=ex[i]; st=e['status']
            m['gstin']+= (e.get('sellerGstin')==t['sellerGstin']) if t['totalReadable'] else 1
            anywrong = (not taxok) or (not tot_ok) or (not bok)
            if anywrong:
                m['wrongAny']+=1
                if st!='READY': m['flaggedWhenWrong']+=1
                else: m['readyButWrong']+=1
        rows.append(dict(id=i,kind=d['kind'],corruption=d['corruption'],total=tot_ok,tax=taxok,buyer=bok,items=iok,status=st,issues=ex.get(i,{}).get('issues'),gotBuyer=o.get('buyer'),gotTax=tax,gotItems=got if not iok else None))
    return m,rows
b,brows=metrics(f'{S}/bench/out_before.json')
a,rows=metrics(f'{S}/bench/out_after.json',f'{S}/bench/out_after_extra.json')
print('BEFORE',b); print('AFTER ',a)
for r in rows:
    if not (r['total'] and r['tax'] and r['buyer'] and r['items']): print({k:v for k,v in r.items() if v is not None})
json.dump(dict(before=b,after=a,rows=rows,beforeRows=brows),open(f'{S}/bench/metrics.json','w'),indent=1,ensure_ascii=False)
