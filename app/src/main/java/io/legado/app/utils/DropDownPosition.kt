package io.legado.app.utils

fun resolveDropDownYOffset(
    anchorTop: Int,
    anchorHeight: Int,
    popupHeight: Int,
    frameTop: Int,
    frameBottom: Int,
    gap: Int,
): Int {
    val anchorBottom = anchorTop + anchorHeight
    val spaceBelow = frameBottom - anchorBottom
    val spaceAbove = anchorTop - frameTop
    val fitsBelow = popupHeight + gap <= spaceBelow
    return if (!fitsBelow && spaceAbove > spaceBelow) {
        -(popupHeight + anchorHeight + gap)
    } else {
        gap
    }
}
