package com.example.data.model

import java.util.UUID

enum class DistillationType(
    val titleFa: String,
    val titleEn: String,
    val descriptionFa: String,
    val iconEmoji: String
) {
    REPAIR_DEFECTS_ONLY(
        "رفع ایرادات متنی (بدون حذف خط)",
        "Zero-Tone Defect Repair",
        "رفع حروف ناخوانا، تبدیل ی/ک عربی و یکدست‌سازی نیم‌فاصله و چیدمان BiDi؛ هیچ خطی افزوده یا حذف نمی‌شود",
        "🩹"
    ),
    FULL_EDITED(
        "متن کامل ویراستاری‌شده",
        "Full Edited Transcript",
        "رفع خطاهای نگارشی، علائم نگارشی و یکدست‌سازی لحن بدون حذف محتوا",
        "📝"
    ),
    EXECUTIVE_SUMMARY(
        "خلاصه اجرایی و نکات کلیدی",
        "Executive Summary",
        "چکیده تصمیمات، اهداف، آمارها و نتایج اصلی مکالمه به صورت بولت‌پوینت",
        "📊"
    ),
    TECHNICAL_DOC(
        "مستندات فنی و راهنمای اجرا",
        "Technical Documentation",
        "تبدیل گفتگو به راهنمای گام‌به‌گام، معماری سیستم و مستندات توسعه",
        "⚙️"
    ),
    QA_FLASHCARDS(
        "بانک پرسش و پاسخ و فلش‌کارت",
        "Q&A Flashcards",
        "استخراج مهم‌ترین سوالات و پاسخ‌های کلیدی گفتگو برای یادگیری و مرور",
        "💡"
    ),
    PURE_CODE(
        "استخراج خالص کدها و ماژول‌ها",
        "Pure Code & Artifacts",
        "جداسازی تمام قطعات کد با توضیحات فنی، فایل‌های مجزا و شیوه اجرا",
        "💻"
    ),
    CUSTOM_REWRITE(
        "پالایش و دستور سفارشی",
        "Custom Prompt Refine",
        "بازنویسی و پردازش هوشمند بر اساس درخواست خاص کاربر",
        "✨"
    )
}

data class AIDistillation(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val type: DistillationType,
    val title: String,
    val content: String,
    val modelUsed: String = "gemini-3.5-flash",
    val createdAt: Long = System.currentTimeMillis()
)
