package com.shopai.app.brain.chat

/**
 * Everyday Tanglish / English words Kai already reads. When a message can't
 * be understood and exactly ONE of its words is outside this list (and isn't
 * a name in the books), Kai asks the owner what that word means instead of
 * guessing — and learns it for that owner after a yes.
 */
object KaiLexicon {
    private val words: Set<String> = """
        a an the is am are was were be been to of in on at by for from with and or but not no yes ok okay so very too also only just
        i me my we our you your he she it they them his her their this that these those what which who whom when where why how much many
        do does did done have has had will would can could should may might must shall get got give gave take took make made go went come came
        today tomorrow yesterday now later soon morning evening night afternoon week month year day days time hour hours minute minutes
        sales sale sold buy bought purchase purchases stock stocks bill bills cash money bank upi gpay balance due pending total amount price
        profit loss expense expenses customer customers supplier suppliers product products item items report summary list show tell send call
        please thanks thank sorry good bad nice great fine super more less low high big small new old all some any every each
        naan nan enakku ennaku neenga nee unga ungal ungalukku avan aval avar avanga ivan ival ivar ivanga naanga namma enga engal
        enna yenna evlo evvalavu eppo eppadi yaar yaaru yaarukku enga edhu ethu edhuku ethuku yen een
        irukku iruku irukka irukkaa irundhuchu irundhu illa illai ille iruka irukkum irukkanum
        romba konjam neraya kammi adhigam athigam kuraiva kooda mattum ellam elam innum ippo appo apram aprom appuram munnadi pinnadi
        inniku innaiku innaikku innikku naalaikku naalaiku nalaiku nethu netru indha intha andha antha idhu adhu ithu athu inga anga
        pannu pannunga panna pannanum panniten pannitten panren pannuren pannalama pannava sollu sollunga solli sonna sonnen sonneenga
        vaa vanga vaanga po ponga poi vandhu vanthu vandhuchu vanthuchu pochu poachu aachu achu aagala aagiduchu aagum
        kudu kuduthen koduthen kudukkanum tharanum thaanga vaangu vanginen vaanginen vangu
        paaru paarunga paathu paakanum theriyum theriyala purinjudha puriyala venum venam vendam vendaam podhum
        kadai kadaila kadaiku shop vyabaaram vyabaram business velai kanakku panam kaasu rokkam baaki bakki selavu varavu
        nalla nallaa semma sari seri aama ama illa okva sapadu saapadu tea coffee
        call phone message remind reminder nyabagam marakkama
        owner sir anna akka thambi thangachi amma appa bro kai
        நான் நீ நீங்க நீங்கள் உன் உங்க உங்கள் என் எனக்கு
    """.trim().split(Regex("""\s+""")).toSet()

    fun knows(word: String): Boolean = word.lowercase() in words
}
