package com.music.bitchord.data.lyrics

import android.icu.text.Transliterator

/** Maps common Traditional Chinese glyphs to the Japanese shinjitai forms. */
internal object JapaneseKanji {
    private val simplifiedToTraditional by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { Transliterator.getInstance("Simplified-Traditional") }.getOrNull()
    }

    private val variants = mapOf(
        '亞' to '亜', '俠' to '侠', '來' to '来', '兩' to '両', '兒' to '児',
        '亂' to '乱', '產' to '産', '藝' to '芸',
        '剝' to '剥', '劍' to '剣', '劃' to '画', '劑' to '剤', '勞' to '労',
        '勳' to '勲', '勵' to '励', '勸' to '勧', '區' to '区', '參' to '参',
        '雙' to '双', '發' to '発', '變' to '変', '嚴' to '厳', '團' to '団',
        '寫' to '写', '帶' to '帯', '號' to '号', '舉' to '挙', '當' to '当',
        '獻' to '献', '價' to '価', '膽' to '胆', '條' to '条', '讓' to '譲',
        '櫻' to '桜', '靈' to '霊', '萬' to '万', '齡' to '齢', '齊' to '斉',
        '齋' to '斎', '驛' to '駅', '驅' to '駆', '澤' to '沢', '瀨' to '瀬',
        '臺' to '台', '鹽' to '塩', '鑄' to '鋳', '鍊' to '錬', '廢' to '廃',
        '聽' to '聴', '燒' to '焼', '脫' to '脱', '謠' to '謡', '豐' to '豊',
        '圍' to '囲', '圖' to '図', '圓' to '円', '壓' to '圧', '壘' to '塁',
        '壞' to '壊', '壯' to '壮', '聲' to '声', '壽' to '寿', '奧' to '奥',
        '學' to '学', '寶' to '宝', '實' to '実', '專' to '専', '將' to '将',
        '舊' to '旧', '權' to '権', '縣' to '県', '繪' to '絵',
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

    /** Converts simplified Chinese glyphs through Traditional Chinese, then Japanese shinjitai. */
    fun convertSimplifiedToJapanese(text: String): String {
        val transliterator = simplifiedToTraditional
        val traditional = if (transliterator == null) {
            text.map { simplifiedFallback[it] ?: it }.joinToString("")
        } else {
            // Lyrics sources are queried concurrently; ICU transliterators are stateful.
            synchronized(transliterator) { transliterator.transliterate(text) }
        }
        // A few KuGou lyrics contain Japanese phrases whose simplified glyphs
        // are not reliably transliterated when Android's ICU data is missing
        // or older. Apply safe phrase-level repairs after the general pass.
        return convert(traditional).replace("言叶", "言葉")
    }

    // A small safe fallback for Android builds where the ICU transform is absent.
    private val simplifiedFallback = mapOf(
        '爱' to '愛', '边' to '邊', '变' to '變', '车' to '車', '长' to '長',
        '传' to '傳', '从' to '從', '东' to '東', '对' to '對', '发' to '發',
        '风' to '風', '国' to '國', '还' to '還', '欢' to '歡', '会' to '會',
        '记' to '記', '间' to '間', '见' to '見', '经' to '經', '开' to '開',
        '来' to '來', '两' to '兩', '灵' to '靈', '乱' to '亂', '马' to '馬',
        '门' to '門', '难' to '難', '脑' to '腦', '气' to '氣', '请' to '請',
        '让' to '讓', '认' to '認', '时' to '時', '实' to '實', '说' to '說',
        '听' to '聽', '为' to '爲', '无' to '無', '现' to '現', '学' to '學',
        '样' to '樣', '艺' to '藝', '应' to '應', '与' to '與', '约' to '約',
        '云' to '雲', '这' to '這', '转' to '轉', '总' to '總',
        '远' to '遠', '离' to '離', '进' to '進', '驯' to '馴',
        '仆' to '僕', '辉' to '輝', '动' to '動',
    )
}
