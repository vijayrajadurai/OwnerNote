# Kai — unit conversion for stock in / out

**Bug fixed:** "Colgate 5 box add pannu" showed "5 box" but Confirm added 5 *pieces*.
Kai's stock tool always posted the number in the product's own unit, whatever unit
the owner said.

## Source of truth (reused, nothing new)

- Stock is kept in the product's **primary (base) unit** — books `ProductEntity.primaryUnit`.
- A product's own conversion is its **secondary unit** — `secondaryUnit` + `conversionMilli`
  (`ProductUnits`: 1 BOX = 12 PCS → 12000). Product-specific and business-scoped (the product
  belongs to one business). No global "box = 12".
- Stock still changes only through the existing inventory engine (`InventoryRepository.stockIn /
  stockOut` → books stock adjustment), only after Confirm, in the base unit.

## Flow

```
owner words → personal memory ("potti" → box) → KaiStock.partsIn ("1 box 3 pieces" → [1 BOX, 3 PCS])
  → KaiUnits.resolve(product, parts, conversions)
      known  → draft:  Input 5 boxes · Conversion 1 box = 12 pieces · Total 60 pieces · Inventory +60 pieces
               [Confirm Stock In] [Edit] [Cancel] (+ [Save: 1 box = 12 pieces] when given in chat)
      unknown → "Owner, 1 box-la evlo pieces irukku?"  (nothing drafted, nothing changed)
               "12" / "12 pieces" / "1 box = 12 pieces" → the draft above
  → Confirm → inventory gets the base quantity shown; the history note keeps "5 boxes = 60 pieces · …"
  → "Done Owner ✅ Colgate — 5 boxes added. Inventory +60 pieces."
```

- Conversions used: the product's saved one; one the owner gave in this chat (this
  conversation only until they tap **Save**); fixed ones only — kg↔gram, litre↔ml, dozen = 12 pieces.
  Anything else is asked.
- Stock out converts the same way (Remaining and the short-stock check use the converted total).
- Mixed: "1 box 3 pieces" = 15 pieces, shown as "1 box + 3 pieces = 15 pieces".
- Edit recalculates from the new quantity / unit; "1 box = 10 pieces" typed while the draft is
  open corrects the conversion and recalculates the same draft.
- Save writes the product's secondary unit through `BooksMasters.updateProduct` in the signed-in
  business. A product keeps one second unit: if a different one is already set, Kai says so and
  uses the new one only in this chat (never overwrites).
- Personal learning only resolves the word ("potti" → box); it never sets a quantity.

Tests: `app/src/test/java/com/shopai/app/brain/chat/KaiUnitConversionTest.kt`.
