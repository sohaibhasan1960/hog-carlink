package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/** HOG CarLink: продавец в его сборке (-Pbrand): логотип и связь (звонок, WhatsApp). Данные - res/values/strings_brand.xml бренда. */
object SellerCard {
    fun logo(c: Context): ImageView = ImageView(c).apply {
        setImageResource(R.drawable.brand_logo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    fun contacts(c: Context): LinearLayout = c.vbox(10).apply {
        pad(c, 16, 14, 16, 14)
        background = c.shape(c.clr(R.color.surf), 20)
        addView(c.text(c.getString(R.string.seller_title), 13f, 500, c.clr(R.color.on2)))
        addView(c.text(c.getString(R.string.seller_name), 18f, 600))
        val address = c.getString(R.string.seller_address)
        if (address.isNotBlank()) addView(c.text(address, 14f, 400, c.clr(R.color.on2)))
        val phone = c.getString(R.string.seller_phone)
        if (phone.isNotBlank()) {
            addView(c.containerButton(c.getString(R.string.seller_call, phone)).apply {
                setOnClickListener { open(c, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
            }, lp(MATCH, WRAP))
        }
        val wa = c.getString(R.string.seller_whatsapp)
        if (wa.isNotBlank()) {
            addView(c.tonalButton(c.getString(R.string.seller_wa)).apply {
                setOnClickListener { open(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$wa"))) }
            }, lp(MATCH, WRAP))
        }
    }

    private fun open(c: Context, i: Intent) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("продавец: не открыть ${i.data}", e)
        }
    }
}
