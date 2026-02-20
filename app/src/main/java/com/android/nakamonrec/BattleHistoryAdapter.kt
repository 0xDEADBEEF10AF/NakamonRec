package com.android.nakamonrec

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView

class BattleHistoryAdapter(
    private val records: MutableList<BattleRecord>,
    private val monsterMaster: List<MonsterData>,
    private val onLongClick: (Int) -> Unit //長押し時のアクション
) : RecyclerView.Adapter<BattleHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val result: TextView = view.findViewById(R.id.textResult)
        val party: TextView = view.findViewById(R.id.textParty)
        val time: TextView = view.findViewById(R.id.textTimestamp)
        val layoutMyMonsters: LinearLayout = view.findViewById(R.id.layoutMyMonsters)
        val layoutEnemyMonsters: LinearLayout = view.findViewById(R.id.layoutEnemyMonsters)
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
        
        // 文字列結合の警告を修正: stringリソースを使用
        holder.party.text = context.getString(R.string.party_label_format, record.partyIndex + 1)
        
        holder.time.text = record.timestamp

        // アイコン表示の更新
        setupMonsterIcons(context, holder.layoutMyMonsters, record.myParty)
        setupMonsterIcons(context, holder.layoutEnemyMonsters, record.enemyParty)
        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }

    private fun setupMonsterIcons(context: Context, layout: LinearLayout, monsterNames: List<String>) {
        layout.removeAllViews()
        val iconSize = (28 * context.resources.displayMetrics.density).toInt()

        monsterNames.forEach { name ->
            val imageView = ImageView(context)
            val params = LinearLayout.LayoutParams(iconSize, iconSize)
            params.setMargins(2, 0, 2, 0)
            imageView.layoutParams = params
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP

            // 名前からMonsterDataを検索してファイル名を取得
            val monster = monsterMaster.find { it.name == name }
            val fileName = monster?.fileName ?: ""

            try {
                // templates/ フォルダにある画像を読み込む
                context.assets.open("templates/$fileName").use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    imageView.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {
                // 未使用変数の警告を修正: e を _ に変更
                imageView.setImageResource(android.R.drawable.ic_menu_help)
            }
            layout.addView(imageView)
        }
    }

    override fun getItemCount(): Int = records.size
}
