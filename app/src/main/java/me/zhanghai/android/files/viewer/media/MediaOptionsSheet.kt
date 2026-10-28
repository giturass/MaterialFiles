package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getColorByAttr
import com.google.android.material.R as MaterialR

internal data class MediaOption(
    val icon: Int,
    val title: CharSequence,
    val subtitle: CharSequence = "",
    val selected: Boolean = false,
    val action: () -> Unit
)

internal class MediaOptionsSheet(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence,
    options: List<MediaOption>,
    availableHeight: Int,
    selectedIndex: Int = 0
) : BottomSheetDialog(context) {
    init {
        val content = layoutInflater.inflate(R.layout.media_sheet, null)
        content.findViewById<TextView>(R.id.media_sheet_title).text = title
        content.findViewById<TextView>(R.id.media_sheet_subtitle).apply {
            text = subtitle
            isVisible = subtitle.isNotEmpty()
        }
        val list = content.findViewById<RecyclerView>(R.id.media_sheet_list)
        val density = context.resources.displayMetrics.density
        list.layoutParams.height = minOf((options.size * 80 * density).toInt(),
            (availableHeight * 0.7f - 128 * density).toInt().coerceAtLeast((120 * density).toInt()))
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = object : RecyclerView.Adapter<OptionHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = OptionHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.media_sheet_item, parent, false))

            override fun getItemCount() = options.size

            override fun onBindViewHolder(holder: OptionHolder, position: Int) {
                val option = options[position]
                val card = holder.itemView as MaterialCardView
                card.setCardBackgroundColor(if (option.selected) context.getColorByAttr(MaterialR.attr.colorSecondaryContainer) else Color.TRANSPARENT)
                card.isSelected = option.selected
                card.findViewById<ImageView>(R.id.media_sheet_icon).setImageResource(option.icon)
                card.findViewById<TextView>(R.id.media_sheet_item_title).text = option.title
                card.findViewById<TextView>(R.id.media_sheet_item_subtitle).apply {
                    text = option.subtitle
                    isVisible = option.subtitle.isNotEmpty()
                }
                card.setOnClickListener { dismiss(); option.action() }
            }
        }
        if (options.isNotEmpty()) list.scrollToPosition(selectedIndex.coerceIn(options.indices))
        setContentView(content)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }

    private class OptionHolder(view: View) : RecyclerView.ViewHolder(view)
}
