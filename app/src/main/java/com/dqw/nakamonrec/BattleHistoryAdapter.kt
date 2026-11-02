package com.dqw.nakamonrec

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
    /**
     * 長押しされた行の「レコード実体」を渡す (position ではない)。
     * 旧実装は onBindViewHolder 時の position をリスナーに捕捉していたが、DiffUtil 更新では
     * 内容不変の行が再バインドされず position が陳腐化し、フィルタ中に別レコードを編集/削除する
     * (静かなデータ破壊) か IndexOutOfBounds でクラッシュする実害があった (2026-09-02 ビーフ確認)。
     * iOS (ForEach がレコード実体を渡す) と同じ設計に揃え、呼び出し側は timestamp で実インデックスを引き直す。
     */
    private val onLongClick: (BattleRecord) -> Unit,
    val onResultClick: (String) -> Unit,   // 引数: タップされた行の result ("WIN" or "LOSE")
    private val onMonsterClick: (String, Boolean) -> Unit
) : RecyclerView.Adapter<BattleHistoryAdapter.ViewHolder>() {

    var filterMyMonsters: List<String> = listOf()
    var filterEnemyMonsters: List<String> = listOf()

    var isFilterMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // モード切替でメモ表示の一時状態はすべて解除 (メモ表示は編集モード専用)
                memoShownTimestamps.clear()
                notifyItemRangeChanged(0, itemCount)
            }
        }

    /**
     * メモ表示に切り替えている行 (timestamp で識別)。画面内だけの一時状態で保存しない。
     * 編集モードでメモ付きの行をタップすると行の内容全体がメモの文章に置き換わり、もう一度タップで戻る (2026-11-02)
     */
    private val memoShownTimestamps = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val result: TextView = view.findViewById(R.id.textResult)
        val party: TextView = view.findViewById(R.id.textParty)
        val time: TextView = view.findViewById(R.id.textTimestamp)
        val vsScore: TextView = view.findViewById(R.id.textVsScore)
        val layoutMyMonsters: LinearLayout = view.findViewById(R.id.layoutMyMonsters)
        val layoutEnemyMonsters: LinearLayout = view.findViewById(R.id.layoutEnemyMonsters)
        val content: View = view.findViewById(R.id.layoutRecordContent)
        val memo: TextView = view.findViewById(R.id.textMemo)
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
        // メモ付きの行は P 番号の横に小さなペン記号を目印として付ける
        val hasMemo = !record.memo.isNullOrBlank()
        holder.party.text = if (hasMemo) "P${record.partyIndex + 1} ✎" else "P${record.partyIndex + 1}"

        // メモ表示の切り替え (編集モードのみ)。表示中は行の内容を INVISIBLE にして高さを保ち、メモだけ見せる
        val showMemo = hasMemo && !isFilterMode && memoShownTimestamps.contains(record.timestamp)
        // 再バインド時は切替アニメーションの途中状態 (平行移動・透明度) を必ず初期値に戻す (ビューは使い回されるため)
        for (v in listOf(holder.content, holder.memo)) {
            v.animate().cancel()
            v.translationX = 0f
            v.alpha = 1f
        }
        holder.content.visibility = if (showMemo) View.INVISIBLE else View.VISIBLE
        holder.memo.visibility = if (showMemo) View.VISIBLE else View.GONE
        holder.memo.text = record.memo ?: ""
        holder.itemView.setOnClickListener { toggleMemoDisplay(holder) }
        // 表示専用: 日付をドット区切り (2026.08.14) にして横幅を詰める
        // (保存形式・CSV は yyyy-MM-dd のまま変更しない。時刻の「:」は影響なし)
        // 日付と時刻を明示的に改行で分け、欄幅に収まるよう自動縮小させる (レイアウト側 autoSize と対)
        holder.time.text = record.timestamp.replace('-', '.').replaceFirst(' ', '\n')

        // スコア表示
        holder.vsScore.visibility = View.GONE

        // クリック設定はモードに依らず常に張り、モード判定と対象レコードの解決は「クリック時」に行う。
        // - バインド時の position/record を捕捉しない (DiffUtil 部分更新で陳腐化するため)
        // - bindingAdapterPosition は NO_POSITION (削除アニメーション中など) を弾く
        holder.result.setOnClickListener {
            if (!isFilterMode) {
                // 編集モードでは WIN/LOSE の部分をタップしてもメモ表示の切り替えとして扱う (行全体と同じ動き)
                toggleMemoDisplay(holder)
                return@setOnClickListener
            }
            val rec = recordAt(holder) ?: return@setOnClickListener
            onResultClick(rec.result)
        }
        holder.itemView.setOnLongClickListener {
            if (isFilterMode) return@setOnLongClickListener false
            val rec = recordAt(holder) ?: return@setOnLongClickListener false
            onLongClick(rec)
            true
        }

        setupMonsterIcons(context, holder.layoutMyMonsters, record.myParty, record.myPartyScores, isFilterMode, false)
        setupMonsterIcons(context, holder.layoutEnemyMonsters, record.enemyParty, record.enemyPartyScores, isFilterMode, true)
    }

    /** 編集モードでメモ付きの行がタップされたとき、通常表示とメモ表示を入れ替える。メモが無い行では何もしない */
    private fun toggleMemoDisplay(holder: ViewHolder) {
        if (isFilterMode) return
        val rec = recordAt(holder) ?: return
        if (rec.memo.isNullOrBlank()) return
        val showMemo = memoShownTimestamps.add(rec.timestamp)
        if (!showMemo) memoShownTimestamps.remove(rec.timestamp)
        // 再バインド (notifyItemChanged) ではなくその場でアニメーションさせる。状態は memoShownTimestamps に
        // 入れてあるので、スクロールで再バインドされても正しい表示になる
        animateMemoSwitch(holder, showMemo)
    }

    /**
     * 通常表示 ⇄ メモ表示の切替アニメーション (iOS 版と同じ「左からスライド + フェード」、2026-11-03)。
     * 出ていく側は左へ少し動きながら消え、入ってくる側は左から少し動きながら現れる
     */
    private fun animateMemoSwitch(holder: ViewHolder, showMemo: Boolean) {
        val outgoing = if (showMemo) holder.content else holder.memo
        val incoming = if (showMemo) holder.memo else holder.content
        val shift = -holder.itemView.width * 0.25f
        outgoing.animate().cancel()
        incoming.animate().cancel()
        outgoing.animate()
            .translationX(shift)
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                // 行の高さは通常表示側で決まるので、通常表示は GONE にせず INVISIBLE で高さを残す
                outgoing.visibility = if (outgoing === holder.content) View.INVISIBLE else View.GONE
                outgoing.translationX = 0f
                outgoing.alpha = 1f
            }
            .start()
        incoming.translationX = shift
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE
        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** クリック時点の adapter position から現在のレコードを取得する (陳腐化・範囲外は null) */
    private fun recordAt(holder: ViewHolder): BattleRecord? {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return null
        return records.getOrNull(pos)
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

            // サムネ間に最小限の隙間 (低解像度端末で 8 体目がタイムスタンプに被らないよう
            // 1dp に抑える。視覚的にもアイコンが分離して見える最小値)
            val containerParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (1 * context.resources.displayMetrics.density).toInt()
            }
            layout.addView(container, containerParams)
        }
    }

    override fun getItemCount(): Int = records.size
}
