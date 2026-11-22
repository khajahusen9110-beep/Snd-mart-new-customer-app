package com.example.data.model

/** First-visit tour text in English and Kannada. {0}, {1} ... are filled in by [t]. */
object TourText {
    private val en = mapOf(
        "tourSkip" to "Skip", "tourNext" to "Next", "tourStart" to "Start shopping",
        "tourWelcomeT" to "Welcome to Sndmart",
        "tourWelcomeB" to "Fresh groceries and hot food from local hotels, delivered to your door in Sindhanur.",
        "tourHelperT" to "New: Quick Helper",
        "tourHelperB" to "Need a hand for a short while? Book a nearby helper for 30, 60 or 90 minutes and pay only for the time you use.",
        "tourTryHelper" to "Try Quick Helper",
        "tourWaT" to "Help on WhatsApp",
        "tourWaB" to "Track your order, report a problem or talk to our team in a WhatsApp chat.",
        "tourRewardsT" to "Rewards and savings",
        "tourRewardsB" to "Scratch cards, cashback in your wallet and Refer & Earn: the more you order, the more you save."
    )
    private val kn = mapOf(
        "tourSkip" to "ಬಿಟ್ಟುಬಿಡಿ", "tourNext" to "ಮುಂದೆ", "tourStart" to "ಶಾಪಿಂಗ್ ಶುರು ಮಾಡಿ",
        "tourWelcomeT" to "Sndmart ಗೆ ಸ್ವಾಗತ",
        "tourWelcomeB" to "ಸಿಂಧನೂರಿನಲ್ಲಿ ತಾಜಾ ದಿನಸಿ ಮತ್ತು ಸ್ಥಳೀಯ ಹೋಟೆಲ್‌ಗಳ ಬಿಸಿ ಊಟ ನಿಮ್ಮ ಮನೆ ಬಾಗಿಲಿಗೆ ತಲುಪುತ್ತದೆ.",
        "tourHelperT" to "ಹೊಸದು: ಕ್ವಿಕ್ ಹೆಲ್ಪರ್",
        "tourHelperB" to "ಸ್ವಲ್ಪ ಹೊತ್ತು ಸಹಾಯ ಬೇಕೇ? 30, 60 ಅಥವಾ 90 ನಿಮಿಷಗಳಿಗೆ ಹತ್ತಿರದ ಸಹಾಯಕರನ್ನು ಬುಕ್ ಮಾಡಿ, ಬಳಸಿದ ಸಮಯಕ್ಕೆ ಮಾತ್ರ ಪಾವತಿಸಿ.",
        "tourTryHelper" to "ಕ್ವಿಕ್ ಹೆಲ್ಪರ್ ನೋಡಿ",
        "tourWaT" to "WhatsApp ನಲ್ಲಿ ಸಹಾಯ",
        "tourWaB" to "ಆರ್ಡರ್ ಟ್ರ್ಯಾಕ್ ಮಾಡಿ, ಸಮಸ್ಯೆ ತಿಳಿಸಿ ಅಥವಾ ನಮ್ಮ ತಂಡದೊಂದಿಗೆ WhatsApp ಚಾಟ್‌ನಲ್ಲಿ ಮಾತನಾಡಿ.",
        "tourRewardsT" to "ಬಹುಮಾನ ಮತ್ತು ಉಳಿತಾಯ",
        "tourRewardsB" to "ಸ್ಕ್ರ್ಯಾಚ್ ಕಾರ್ಡ್, ವಾಲೆಟ್‌ನಲ್ಲಿ ಕ್ಯಾಶ್‌ಬ್ಯಾಕ್ ಮತ್ತು ರೆಫರ್ & ಅರ್ನ್: ಹೆಚ್ಚು ಆರ್ಡರ್ ಮಾಡಿದಷ್ಟೂ ಹೆಚ್ಚು ಉಳಿತಾಯ."
    )

    fun t(lang: String, key: String, vararg args: Any?): String {
        var s = (if (lang == "kn") kn else en)[key] ?: en[key] ?: key
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a?.toString() ?: "") }
        return s
    }
}
