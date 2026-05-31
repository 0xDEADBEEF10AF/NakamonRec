package com.android.nakamonrec

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class BattleHistoryAdapter(
    private var records: MutableList<BattleRecord>,
    private val monsterMaster: List<MonsterData>,
    private val onLongClick: (Int) -> Unit,
    val onResultClick: () -> Unit,
    private val onMonsterClick: (String, Boolean) -> Unit
) : RecyclerView.Adapter<BattleHistoryAdapter.ViewHolder>() {

    var filterMyMonsters: List<String> = listOf()
    var filterEnemyMonsters: List<String> = listOf()

    var isFilterMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val result: TextView = view.findViewById(R.id.textResult)
        val party: TextView = view.findViewById(R.id.textParty)
        val time: TextView = view.findViewById(R.id.textTimestamp)
        val vsScore: TextView = view.findViewById(R.id.textVsScore)
        val layoutMyMonsters: LinearLayout = view.findViewById(R.id.layoutMyMonsters)
        val layoutEnemyMonsters: LinearLayout = view.findViewById(R.id.layoutEnemyMonsters)
    }

    fun updateData(newRecords: List<BattleRecord>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = records.size
            override fun getNewListSize(): Int = newRecords.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return records[oldItemPosition].timestamp == newRecords[newItemPosition].timestamp
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return records[oldItemPosition] == newRecords[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        records = newRecords.toMutableList()
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_battle_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        val context = holder.itemView.context

        holder.result.text = record.result
        holder.result.setTextColor(if (record.result == "WIN") "#F09199".toColorInt() else "#90D7EC".toColorInt())
        holder.party.text = "P${record.partyIndex + 1}"
        holder.time.text = record.timestamp

        // スコア表示
        holder.vsScore.visibility = View.GONE

        // モードに応じたクリック設定
        if (isFilterMode) {
            holder.result.setOnClickListener { onResultClick() }
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.result.setOnClickListener(null)
            holder.itemView.setOnLongClickListener {
                onLongClick(position)
                true
            }
        }

        setupMonsterIcons(context, holder.layoutMyMonsters, record.myParty, record.myPartyScores, isFilterMode, false)
        setupMonsterIcons(context, holder.layoutEnemyMonsters, record.enemyParty, record.enemyPartyScores, isFilterMode, true)
    }

    private fun setupMonsterIcons(
        context: Context,
        layout: LinearLayout,
        monsterNames: List<String>,
        scores: List<Double>?,
        clickable: Boolean,
        isEnemy: Boolean
    ) {
        layout.removeAllViews()
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.battle_history_icon_size)

        monsterNames.forEach { name ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
            }

            val imageView = ImageView(context)
            val params = LinearLayout.LayoutParams(iconSize, iconSize)
            imageView.layoutParams = params
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP

            // 角丸 (iOS の RoundedRectangle(cornerRadius: 6) と同程度の見た目)
            val cornerRadiusPx = 6 * context.resources.displayMetrics.density
            imageView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            imageView.clipToOutline = true

            val monster = monsterMaster.find { it.name == name }
            val fileName = monster?.fileName ?: ""

            try {
                context.assets.open("templates/$fileName").use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    imageView.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {
                imageView.setImageResource(android.R.drawable.ic_menu_help)
            }

            val isSelected = if (isEnemy) filterEnemyMonsters.contains(name) else filterMyMonsters.contains(name)
            
            if (isSelected) {
                val highlightColor = if (isEnemy) Color.parseColor("#90D7EC") else Color.parseColor("#F09199")
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 4 * context.resources.displayMetrics.density
                    setStroke((2 * context.resources.displayMetrics.density).toInt(), highlightColor)
                    setColor(Color.argb(40, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor)))
                }
                imageView.background = bg
                val p = (2 * context.resources.displayMetrics.density).toInt()
                imageView.setPadding(p, p, p, p)
                imageView.setColorFilter(Color.argb(70, 255, 255, 255), android.graphics.PorterDuff.Mode.SRC_ATOP)
                imageView.scaleX = 1.15f
                imageView.scaleY = 1.15f
            } else {
                imageView.background = null
                imageView.setPadding(0, 0, 0, 0)
                imageView.colorFilter = null
                imageView.scaleX = 1.0f
                imageView.scaleY = 1.0f
            }

            container.addView(imageView)

            if (clickable && name.isNotEmpty()) {
                container.setOnClickListener { onMonsterClick(name, isEnemy) }
            }

            // サムネ間に隙間 (iOS の HStack spacing と同様の見た目に揃える)
            val containerParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (3 * context.resources.displayMetrics.density).toInt()
            }
            layout.addView(container, containerParams)
        }
    }

    override fun getItemCount(): Int = records.size
}
