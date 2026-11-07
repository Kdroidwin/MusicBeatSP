package com.music.bitchord.data.lyrics

/** Maps common Traditional Chinese glyphs to the Japanese shinjitai forms. */
internal object JapaneseKanji {
    private val variants = mapOf(
        '亞' to '亜', '俠' to '侠', '來' to '来', '兩' to '両', '兒' to '児',
        '剝' to '剥', '劍' to '剣', '劃' to '画', '劑' to '剤', '勞' to '労',
        '勳' to '勲', '勵' to '励', '勸' to '勧', '區' to '区', '參' to '参',
        '雙' to '双', '發' to '発', '變' to '変', '嚴' to '厳', '團' to '団',
        '圍' to '囲', '圖' to '図', '圓' to '円', '壓' to '圧', '壘' to '塁',
        '壞' to '壊', '壯' to '壮', '聲' to '声', '壽' to '寿', '奧' to '奥',
        '學' to '学', '寶' to '宝', '實' to '実', '專' to '専', '將' to '将',
        '對' to '対', '屬' to '属', '巖' to '巌', '廣' to '広', '廳' to '庁',
        '彈' to '弾', '徑' to '径', '從' to '従', '德' to '徳', '惡' to '悪',
        '慘' to '惨', '應' to '応', '懷' to '懐', '戀' to '恋', '戲' to '戯',
        '戶' to '戸', '擔' to '担', '據' to '拠', '擴' to '拡', '擇' to '択',
        '擊' to '撃', '攝' to '摂', '數' to '数', '斷' to '断', '會' to '会',
        '樂' to '楽', '歲' to '歳', '歷' to '歴', '歸' to '帰', '殘' to '残',
        '殼' to '殻', '氣' to '気', '淨' to '浄', '淺' to '浅', '濕' to '湿',
        '濟' to '済', '瀧' to '滝', '灣' to '湾', '爲' to '為', '狀' to '状',
        '獨' to '独', '獸' to '獣', '盜' to '盗', '盡' to '尽', '眾' to '衆',
        '禪' to '禅', '稱' to '称', '經' to '経', '綠' to '緑', '緣' to '縁',
        '總' to '総', '縱' to '縦', '纖' to '繊', '續' to '続', '聯' to '連',
        '聰' to '聡', '腦' to '脳', '臟' to '臓', '與' to '与', '艷' to '艶',
        '處' to '処', '虛' to '虚', '裝' to '装', '裡' to '裏', '覺' to '覚',
        '觀' to '観', '觸' to '触', '說' to '説', '譯' to '訳', '證' to '証',
        '讀' to '読', '賣' to '売', '賴' to '頼', '贊' to '賛', '軀' to '躯',
        '轉' to '転', '辭' to '辞', '辯' to '弁', '邊' to '辺', '鄉' to '郷',
        '醫' to '医', '釋' to '釈', '錄' to '録', '鐵' to '鉄', '關' to '関',
        '隨' to '随', '險' to '険', '隱' to '隠', '靜' to '静', '顏' to '顔',
        '顯' to '顕', '餘' to '余', '體' to '体', '髮' to '髪', '鬥' to '闘',
        '麥' to '麦', '黃' to '黄', '黨' to '党', '點' to '点', '齒' to '歯',
        '龍' to '竜', '龜' to '亀',
    )

    fun convert(text: String): String = buildString(text.length) {
        text.forEach { character -> append(variants[character] ?: character) }
    }
}
