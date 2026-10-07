"""
Generates 50 synthetic GST invoices as OCR *text* (not images) with ground truth,
for a deterministic before/after parser benchmark. Seeded: same output every run.

Each invoice has a layout family and, for some, an OCR corruption that the
34-image OnePlus stress test reported (dropped decimal, Tamil letters inside
English words, buyer merged with the right-hand column, rate printed after the
amount, amount on the line below the rate, heavy text loss).
"""
import json, random, sys
from decimal import Decimal, ROUND_HALF_UP

random.seed(20261007)
C = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

def gstin(state="33"):
    letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    base = state + "".join(random.choice(letters) for _ in range(5)) + "".join(random.choice("0123456789") for _ in range(4)) + random.choice(letters) + "1Z"
    s = 0
    for i in range(14):
        p = C.index(base[i]) * (1 if i % 2 == 0 else 2)
        s += p // 36 + p % 36
    return base + C[(36 - s % 36) % 36]

def money(d):
    d = Decimal(d).quantize(Decimal("0.01"), ROUND_HALF_UP)
    whole, frac = f"{d:.2f}".split(".")
    n = whole
    if len(n) > 3:
        head, tail = n[:-3], n[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:]); head = head[:-2]
        if head: groups.insert(0, head)
        n = ",".join(groups + [tail])
    return f"{n}.{frac}"

SHOPS = ["SRI LAKSHMI TRADERS", "MURUGAN AGENCIES", "KAVERI DISTRIBUTORS", "SAI ENTERPRISES", "ANBU WHOLESALE",
         "VINAYAKA STORES", "SELVAM HARDWARES", "PRIYA TEXTILES", "GANESH ELECTRICALS", "ARUL PHARMA"]
BUYERS = ["Kumar Stores", "Ravi Traders", "Meena Supermarket", "Praba Mart", "Selvi Provisions", "Arun Medicals", None]
PRODUCTS = [("Basmati Rice", "1006", "kg"), ("Sugar", "1701", "kg"), ("Toor Dal", "0713", "kg"), ("Sunflower Oil", "1512", "ltr"),
            ("Colgate Toothpaste 100g", "3306", "nos"), ("Dettol Soap", "3401", "pcs"), ("LED Bulb 9W", "8539", "nos"),
            ("Copper Wire 1.5 sqmm", "8544", "mtr"), ("Cotton Saree Premium Long Name Edition", "5208", "nos"),
            ("Cashew Nuts", "0801", "kg"), ("Wall Putty", "3214", "bag"), ("Notebook 200 Pages", "4820", "nos")]
RATES = [Decimal("5"), Decimal("12"), Decimal("18"), Decimal("28")]

def q2(x): return Decimal(x).quantize(Decimal("0.01"), ROUND_HALF_UP)

def invoice(i):
    kind = ["single", "multi", "igst", "discount", "roundoff", "many", "decimalqty", "tamil", "nobuyer", "single"][i % 10]
    corrupt = {7: "decimal_drop", 13: "tamil_inject", 19: "buyer_merge", 21: "rate_after", 27: "rate_nextline",
               33: "text_loss", 37: "decimal_drop", 41: "tamil_inject", 44: "buyer_merge", 48: "rate_after"}.get(i)
    shop = SHOPS[i % len(SHOPS)]
    buyer = None if kind == "nobuyer" else BUYERS[i % (len(BUYERS) - 1)]
    sg, bg = gstin(), gstin("33" if kind != "igst" else "29")
    n_items = 12 if kind == "many" else random.randint(1, 4)
    rates = [random.choice(RATES)] if kind not in ("multi",) else random.sample(RATES[:3], 3)
    items = []
    for k in range(n_items):
        name, hsn, unit = PRODUCTS[(i + k) % len(PRODUCTS)]
        qty = Decimal(random.choice(["0.5", "1.25", "2.5"])) if kind == "decimalqty" else Decimal(random.randint(1, 20))
        price = q2(Decimal(random.randint(1000, 90000)) / 100)
        amount = q2(qty * price)
        items.append(dict(name=name, hsn=hsn, unit=unit, qty=qty, price=price, amount=amount, rate=rates[k % len(rates)]))
    gross = sum(it["amount"] for it in items)
    discount = q2(gross * Decimal("0.05")) if kind == "discount" else Decimal("0")
    taxable = gross - discount
    # Tax per rate on that rate's share (discount spread proportionally).
    per_rate = {}
    for it in items:
        share = it["amount"] - (q2(discount * it["amount"] / gross) if discount else 0)
        per_rate[it["rate"]] = per_rate.get(it["rate"], Decimal(0)) + share
    lines_tax, tax = [], Decimal(0)
    for rate, base in sorted(per_rate.items()):
        if kind == "igst":
            t = q2(base * rate / 100); tax += t
            lines_tax.append(("IGST", rate, t))
        else:
            half = q2(base * rate / 200); tax += 2 * half
            lines_tax.append(("CGST", rate / 2, half)); lines_tax.append(("SGST", rate / 2, half))
    raw_total = taxable + tax
    total = raw_total.quantize(Decimal("1"), ROUND_HALF_UP) if kind == "roundoff" else raw_total
    roundoff = total - raw_total

    L = [shop]
    if kind == "tamil": L.append("ஸ்ரீ லட்சுமி டிரேடர்ஸ்")
    L += [f"GSTIN: {sg}", "TAX INVOICE"]
    inv = f"{shop[:3]}-{1000 + i}"
    if corrupt == "buyer_merge" and buyer:
        L += [f"Bill To:            Invoice No: {inv}", f"{buyer}         Date: {10 + i % 18:02d}/09/2026"]
    else:
        L += [f"Invoice No: {inv}", f"Date: {10 + i % 18:02d}/09/2026"]
        if buyer: L += [f"Bill To: {buyer}"]
    if buyer: L.append(f"GSTIN: {bg}")
    L.append("Description HSN Qty Rate Amount")
    for k, it in enumerate(items):
        q = f"{it['qty'].normalize():f}"
        L.append(f"{it['name']} {it['hsn']} {q} {it['unit']} {money(it['price'])} {money(it['amount'])}")
    if discount: L.append(f"Discount 5% {money(discount)}")
    L.append(f"Taxable Value {money(taxable)}")
    for (label, rate, amt) in lines_tax:
        r = f"{rate.normalize():f}"
        if corrupt == "rate_after": L.append(f"{label} {money(amt)} @ {r}%")
        elif corrupt == "rate_nextline": L += [f"{label} @ {r}%", money(amt)]
        else: L.append(f"{label} @ {r}% {money(amt)}")
    if roundoff: L.append(f"Round Off {'(-) ' if roundoff < 0 else ''}{money(abs(roundoff))}")
    L.append(f"Grand Total {money(total)}")
    L += ["Terms & Conditions: Goods once sold will not be taken back", "Authorised Signatory"]

    truth_tax_readable = True
    if corrupt == "decimal_drop":
        # The first tax line's decimal point is lost: "436.20" → "43620".
        for j, line in enumerate(L):
            if line.startswith(("CGST", "IGST")):
                parts = line.rsplit(" ", 1); L[j] = parts[0] + " " + parts[1].replace(".", "").replace(",", ""); break
        truth_tax_readable = False
    if corrupt == "tamil_inject":
        L = [l.replace("Taxable", "Taxaபble").replace("Invoice", "Invoிce").replace("Terms", "Teரms") for l in L]
    if corrupt == "text_loss":
        L = L[:3]
    return dict(id=f"TXT-{i + 1:03d}", kind=kind, corruption=corrupt, text="\n".join(L),
                truth=dict(total=f"{total:.2f}", tax=f"{tax:.2f}", taxReadable=truth_tax_readable and corrupt != "text_loss",
                           totalReadable=corrupt != "text_loss", buyer=buyer if corrupt != "text_loss" else None,
                           sellerGstin=sg, items=[[it["name"], f"{it['amount']:.2f}"] for it in items] if corrupt != "text_loss" else []))

data = [invoice(i) for i in range(50)]
json.dump(data, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(data), "invoices")
