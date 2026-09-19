package com.nao.md.project

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Layout sederhana yang menata anak-anaknya secara horizontal dan otomatis
 * turun ke baris berikutnya bila lebar tidak cukup. Dipakai untuk deretan
 * chip pilihan (mis. "HD No Watermark", "Audio", "720p") supaya label panjang
 * tidak pernah terpotong atau menabrak tepi kartu.
 */
class NaoFlowLayout(
    context: Context,
    private val gapPx: Int
) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(
                child,
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (lineWidth > 0 && lineWidth + gapPx + cw > maxWidth) {
                totalHeight += lineHeight + gapPx
                lineWidth = cw
                lineHeight = ch
            } else {
                lineWidth = if (lineWidth == 0) cw else lineWidth + gapPx + cw
                lineHeight = maxOf(lineHeight, ch)
            }
        }
        totalHeight += lineHeight
        setMeasuredDimension(
            maxWidth + paddingLeft + paddingRight,
            totalHeight + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            if (x > paddingLeft && x + cw > paddingLeft + maxWidth) {
                x = paddingLeft
                y += lineHeight + gapPx
                lineHeight = 0
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw + gapPx
            lineHeight = maxOf(lineHeight, ch)
        }
    }
}
