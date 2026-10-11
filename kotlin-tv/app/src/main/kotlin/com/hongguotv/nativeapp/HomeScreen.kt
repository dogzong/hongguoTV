// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(27f,TvStyle.text).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private val meta=label(12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    init {
        orientation=VERTICAL
        val dp=resources.displayMetrics.density
        setPadding(0,(7*dp).toInt(),0,(12*dp).toInt())
        addView(title)
        addView(meta,LayoutParams(-1,-2).apply { topMargin=(5*dp).toInt() })
        show(null,"")
    }
    fun show(series: Series?,status: String) {
        val nextTitle=series?.title ?: "下一段好故事，从这里开始"
        val nextMeta=if(series==null) "浏览片库，选择你想看的剧" else listOf(status,series.badge,series.tags).flatMap { it.split('·') }.map(String::trim).filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  /  ")
        if(title.text.toString()!=nextTitle) title.text=nextTitle
        if(meta.text.toString()!=nextMeta) meta.text=nextMeta
    }
}

/** Bounded horizontal shelves with stable D-pad selection and per-row scroll positions. */
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit,
    private val selectRecommendation: (String)->Unit = {},
    private val activeRecommendation: ()->String = { "all" },
    private val loadMoreRecommendation: ()->Unit = {},
    private val startDiscovery: ()->Unit = {}
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private data class Card(val key: String,val view: LinearLayout,val image: ImageView?,val title: TextView,val label: TextView,val progress: ProgressBar?,var entry: Entry?,var pending: Boolean=false)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: MutableList<Card>)
    private val accent=TvStyle.accent
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(focus: Boolean)=TvStyle.card(context,focus)
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL }
    private val rows=mutableListOf<Row>()
    private val cards=linkedMapOf<String,Card>()
    private var shelves=emptyList<Shelf>()
    private var footerMore: (() -> Unit)?=null
    private var moreButton: View?=null
    private var lastFocus=""
    private var lastRow: String?=null
    private var restoring=false
    private var revision=0
    private var recommendationBlock: View?=null
    private var appendRecommendation: ((Entry)->Unit)?=null
    private val recommendationChips=linkedMapOf<String,View>()
    val refresh=label("正在更新热门…",13f).apply { id=View.generateViewId(); tag="home:refresh"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); if(f) lastFocus=tag.toString() } }
    init {
        orientation=VERTICAL; scroll.addView(contents); addView(scroll,LayoutParams(-1,0,1f))
        scroll.setOnScrollChangeListener { _,_,y,_,_ ->
            if(contents.height>0 && y+scroll.height>=contents.height-dp(420) && shelves.any { it.key=="recommend-feed" }) loadMoreRecommendation()
        }
    }

    fun capturePosition(): Position {
        val key=findFocus()?.tag?.toString()?.takeIf { it in cards || it=="home:refresh" || it=="home:more" } ?: lastFocus
        val row=rows.indexOfFirst { item -> item.cards.any { it.key==key } }
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==key } ?: 0
        return Position(key,rows.getOrNull(row)?.key,column.coerceAtLeast(0),row.coerceAtLeast(0),scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }

    /** Append newly loaded recommendations in place; rebuilding the grid loses focus and scroll. */
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) {
        val oldFeed=shelves.firstOrNull { it.key=="recommend-feed" }
        val nextFeed=next.firstOrNull { it.key=="recommend-feed" }
        fun shelfShape(items: List<Shelf>)=items.filterNot { it.key=="recommend-feed" }.map { Triple(it.key,it.title,it.entries) }
        val otherShelvesUnchanged=shelfShape(shelves)==shelfShape(next)
        if(oldFeed!=null && nextFeed!=null && otherShelvesUnchanged && nextFeed.entries.size>=oldFeed.entries.size &&
            nextFeed.entries.take(oldFeed.entries.size)==oldFeed.entries && nextFeed.title==oldFeed.title) {
            shelves=next
            nextFeed.entries.drop(oldFeed.entries.size).forEach { appendRecommendation?.invoke(it) }
            return
        }
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
    }

    fun scrollToRecommendation() {
        recommendationBlock?.let { block ->
            post {
                if(block.isLaidOut) scroll.smoothScrollTo(0,block.top.coerceAtLeast(0))
                else block.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                        block.removeOnLayoutChangeListener(this)
                        scroll.smoothScrollTo(0,block.top.coerceAtLeast(0))
                    }
                })
            }
        }
    }

    fun focusRecommendation(type: String) {
        recommendationChips[type]?.requestFocus()
    }

    /** Update an existing card without replacing views or moving focus. Structural changes use updateShelves. */
    fun updateEntry(shelfKey: String,entry: Entry): Boolean {
        val card=cards["$shelfKey:${entry.series.id}"] ?: return false
        if(card.entry?.series?.cover!=entry.series.cover) { card.image?.setImageDrawable(null) }
        card.entry=entry; card.title.text=entry.series.title; card.label.text=entry.label
        card.progress?.apply { progress=entry.progress ?: 0; visibility=if(entry.progress==null) INVISIBLE else VISIBLE }
        card.view.contentDescription="${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        if(lastFocus==card.key) refreshSelection()
        loadNearbySelection()
        return true
    }

    fun refreshSelection() { cards[lastFocus]?.entry?.let { selected(it.series,it.label) } }

    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null) {
        val previous=position ?: if(rows.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++; val ticket=revision; restoring=true
        contents.removeAllViews(); rows.clear(); cards.clear(); moreButton=null; lastRow=null
        recommendationBlock=null; appendRecommendation=null; recommendationChips.clear()
        shelves.filter { it.entries.isNotEmpty() || it.more!=null }.forEach { shelf ->
            val block=LinearLayout(context).apply { orientation=VERTICAL }
            val heading=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(4),dp(2),dp(9)) }
            heading.addView(View(context).apply { background=TvStyle.shape(context,accent,radius=2) },LayoutParams(dp(3),dp(14)).apply { rightMargin=dp(8) })
            heading.addView(label(shelf.title,20f).apply { setTypeface(null,Typeface.BOLD) },if(shelf.key=="recommend-feed") LayoutParams(-1,-2) else LayoutParams(0,-2,1f))
            if(shelf.key=="recommend-feed") {
                heading.getChildAt(1).layoutParams=LayoutParams(0,-2,1f)
                val discovery=label("随机刷剧  ·  OK 开始  ·  上下换剧",13f,TvStyle.text).apply {
                    id=View.generateViewId(); tag="home:discovery"; isFocusable=true; isFocusableInTouchMode=true
                    setPadding(dp(12),dp(9),dp(12),dp(9)); background=shape(false)
                    setOnFocusChangeListener { v,f -> v.background=shape(f) }
                    setOnClickListener { startDiscovery() }
                }
                heading.addView(discovery)
            } else heading.addView(label("左右选剧  ·  上下切换分区",10f,TvStyle.muted))
            block.addView(heading)
            if(shelf.key=="recommend-feed") {
                val filters=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(10),0,dp(12)) }
                listOf("all" to "短剧推荐","human" to "真人剧","comic" to "漫剧","ai" to "AI剧").forEach { (type,name) ->
                    val chip=TextView(context).apply {
                        id=View.generateViewId(); text=name; textSize=17f; includeFontPadding=false; gravity=Gravity.CENTER
                        setTextColor(if(type==activeRecommendation()) TvStyle.text else TvStyle.muted)
                        isFocusable=true; isFocusableInTouchMode=true; minHeight=dp(48); setPadding(dp(14),dp(10),dp(14),dp(10))
                        background=TvStyle.card(context,type==activeRecommendation())
                        setOnFocusChangeListener { v,f ->
                            if(type==activeRecommendation() || f) v.background=TvStyle.card(context,true)
                            else v.background=TvStyle.card(context,false)
                        }
                        setOnClickListener { selectRecommendation(type) }
                    }
                    filters.addView(chip,LayoutParams(0,-2,1f).apply { if(type!="all") leftMargin=dp(8) })
                    recommendationChips[type]=chip
                }
                block.addView(filters)
                recommendationBlock=block
            }
            val horizontal=HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)) }
            val gridMode=shelf.key=="recommend-feed" || shelf.key.startsWith("recommend-")
            val grid=GridLayout(context).apply { columnCount=6; orientation=GridLayout.HORIZONTAL; useDefaultMargins=false }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.TOP }
            if(gridMode) { horizontal.addView(grid,LayoutParams(-1,-2)); block.addView(horizontal) }
            else { horizontal.addView(line); block.addView(horizontal) }
            val rowCards=mutableListOf<Card>()
            lateinit var appendCard: (Entry?)->Unit
            fun addCard(entry: Entry?,moreAction: (() -> Unit)?=null) {
                val width=if(gridMode) ((resources.displayMetrics.widthPixels*.9f-dp(48))/6).toInt().coerceAtLeast(dp(92)) else ((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
                val key=if(entry!=null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
                val view=LinearLayout(context).apply { orientation=VERTICAL; id=View.generateViewId(); tag=key; isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=shape(false) }
                val image=if(entry!=null) ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO } else null
                view.addView(image ?: label("›",36f).apply { gravity=Gravity.CENTER },LayoutParams(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val title=label(entry?.series?.title ?: "查看全部",14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) }
                val badge=label(entry?.label ?: shelf.title,12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) }
                val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=entry?.progress ?: 0; visibility=if(entry?.progress==null) INVISIBLE else VISIBLE; progressTintList=android.content.res.ColorStateList.valueOf(accent) }
                view.addView(title); view.addView(badge); view.addView(progress,LayoutParams(-1,dp(3)).apply { topMargin=dp(4) })
                val card=Card(key,view,image,title,badge,progress,entry)
                view.contentDescription=entry?.let { "${shelf.title}，${it.series.title}，${it.label}" } ?: "查看全部${shelf.title}"
                view.setOnClickListener { lastFocus=key; remember(key); card.entry?.let { open(it.series,it.resume) } ?: moreAction?.invoke() }
                if(entry!=null) {
                    view.setOnLongClickListener { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) }; true }
                    view.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) } }; true } else false }
                }
                view.setOnFocusChangeListener { _,focused ->
                    view.background=shape(focused)
                    if(focused) {
                        lastFocus=key; remember(key); card.entry?.let { selected(it.series,it.label) }
                        if(!restoring) {
                            loadNearbySelection()
                            if(lastRow!=shelf.key) scroll.post { if(revision==ticket && view.hasFocus()) reveal(block) }
                        }
                        lastRow=shelf.key
                    }
                }
                if(gridMode) {
                    val index=rowCards.size
                    val params=GridLayout.LayoutParams(GridLayout.spec(index/6),GridLayout.spec(index%6,1f)).apply {
                        this.width=0; this.height=LayoutParams.WRAP_CONTENT
                        leftMargin=dp(4); rightMargin=dp(4); topMargin=dp(3); bottomMargin=dp(9)
                    }
                    grid.addView(view,params)
                    image?.layoutParams=LayoutParams(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt())
                } else line.addView(view,LayoutParams(width,-1).apply { rightMargin=dp(14) })
                rowCards+=card; cards[key]=card
            }
            appendCard={ entry -> addCard(entry) }
            shelf.entries.forEach { addCard(it) }
            if(shelf.key=="recommend-feed") appendRecommendation={ entry ->
                appendCard(entry)
                bindCardFocus(top)
                loadFeedNearViewport()
            }
            shelf.more?.let { action -> addCard(null,action) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(16) }); rows+=Row(shelf.key,block,horizontal,rowCards)
        }
        if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        contents.addView(refresh,LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val footer=mutableListOf<View>(refresh)
        if(more!=null) {
            val next=label("更多热门  ›",15f).apply { id=View.generateViewId(); tag="home:more"; isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(12),dp(12),dp(12),dp(12)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); if(f) lastFocus=tag.toString() }; setOnClickListener { more() } }
            contents.addView(next,LayoutParams(-1,-2).apply { topMargin=dp(8) }); footer+=next; moreButton=next
        }
        bindCardFocus(top)
        footer.forEachIndexed { i,v -> v.nextFocusUpId=if(i==0) rows.lastOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id else footer[i-1].id; v.nextFocusDownId=footer.getOrNull(i+1)?.id ?: v.id }
        top.nextFocusDownId=firstId()
        val preferred=focus.ifBlank { previous?.focus.orEmpty() }
        val target=target(preferred,previous)
        if(requestFocus) target.requestFocus()
        post layout@{
            if(revision!=ticket) return@layout
            fitArtwork()
            post restore@{
                if(revision!=ticket) return@restore
                if(previous!=null) restoreOffsets(previous)
                restoring=false
                if(requestFocus && previous==null) rows.firstOrNull { row -> row.cards.any { it.view===target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    private fun bindCardFocus(top: View) {
        rows.forEachIndexed { i,row -> row.cards.forEachIndexed { j,card ->
            if(row.key=="recommend-feed") {
                val col=j%6
                val indexInColumn=j/6
                val sameColumnBefore=j-6
                val sameColumnAfter=j+6
                card.view.nextFocusLeftId=row.cards.getOrNull(if(col>0) j-1 else j)?.view?.id ?: card.view.id
                card.view.nextFocusRightId=row.cards.getOrNull(if(col<5) j+1 else j)?.view?.id ?: card.view.id
                card.view.nextFocusUpId=when {
                    indexInColumn>0 -> row.cards.getOrNull(sameColumnBefore)?.view?.id ?: top.id
                    else -> recommendationChips[activeRecommendation()]?.id ?: top.id
                }
                card.view.nextFocusDownId=when {
                    row.cards.getOrNull(sameColumnAfter)!=null -> row.cards[sameColumnAfter].view.id
                    row.cards.getOrNull(j+1)!=null -> row.cards[j+1].view.id
                    else -> refresh.id
                }
            } else {
                card.view.nextFocusLeftId=row.cards[(j-1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId=row.cards[(j+1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId=if(i==0) top.id else rows[i-1].cards[j.coerceAtMost(rows[i-1].cards.lastIndex)].view.id
                card.view.nextFocusDownId=if(i==rows.lastIndex) refresh.id else rows[i+1].cards[j.coerceAtMost(rows[i+1].cards.lastIndex)].view.id
            }
        } }
    }

    private fun loadFeedNearViewport() {
        post {
            val feed=rows.firstOrNull { it.key=="recommend-feed" } ?: return@post
            val visible=feed.cards.indices.filter { index ->
                val rect=android.graphics.Rect()
                feed.cards[index].view.getGlobalVisibleRect(rect)
            }
            visible.forEach { requestCover(feed.cards[it]) }
            val last=visible.maxOrNull() ?: return@post
            for(index in last+1..(last+6).coerceAtMost(feed.cards.lastIndex)) requestCover(feed.cards[index])
        }
    }

    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        restoring=true
        if(requestFocus) target(position.focus,position).requestFocus()
        post { restoreOffsets(position); restoring=false; loadNearbySelection() }
    }

    private fun target(key: String,position: Position?): View {
        if(key=="home:refresh") return refresh
        if(key=="home:more") moreButton?.let { return it }
        cards[key]?.let { return it.view }
        if(position!=null) {
            val row=rows.firstOrNull { it.key==position.shelf } ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }
    private fun restoreOffsets(position: Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0,0) }
        scroll.scrollTo(0,position.vertical)
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }
    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal=row.horizontal
            val left=horizontal.scrollX
            val right=left+horizontal.width-horizontal.paddingRight
            val target=when { card.left<left -> card.left; card.right>right -> card.right-horizontal.width+horizontal.paddingRight; else -> left }
            if(target!=left) horizontal.scrollTo(target.coerceAtLeast(0),0)
        }
        reveal(row.block,false)
    }
    private fun reveal(block: View,smooth: Boolean=true) {
        val target=if(smooth) block.top else when { block.top<scroll.scrollY -> block.top; block.bottom>scroll.scrollY+scroll.height -> if(block.height>=scroll.height) block.top else block.bottom-scroll.height; else -> scroll.scrollY }.coerceAtLeast(0)
        if(target!=scroll.scrollY) { if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target) }
    }
    private fun loadNearbySelection() {
        if(restoring) return
        val row=rows.indexOfFirst { it.cards.any { card -> card.key==lastFocus } }.coerceAtLeast(0)
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items -> for(index in (column-1).coerceAtLeast(0)..(column+6).coerceAtMost(items.lastIndex)) requestCover(items[index]) }
        rows.getOrNull(row+1)?.cards?.take(6)?.forEach(::requestCover)
    }
    private fun requestCover(card: Card) {
        val image=card.image ?: return
        if(card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket=revision
        fun start() {
            card.pending=false
            if(ticket!=revision || !image.isAttachedToWindow || image.width<=0 || image.height<=0) return
            loadCover(image,card.entry!!.series.cover)
        }
        if(image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending=true
            image.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    image.removeOnLayoutChangeListener(this); start()
                }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { reloadArtwork() } }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if(w!=oldw || h!=oldh) post { fitArtwork(); loadNearbySelection() } }
    private fun fitArtwork() {
        if(height<=0) return
        rows.filterNot { it.key.startsWith("recommend-") }.forEach { row ->
            val textHeight=row.cards.maxOf { item -> item.view.paddingTop+item.view.paddingBottom+(1 until item.view.childCount).sumOf { child ->
                val view=item.view.getChildAt(child); val params=view.layoutParams as LayoutParams
                view.measuredHeight+params.topMargin+params.bottomMargin
            } }
            val desired=((row.cards.first().view.width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
            val imageHeight=minOf(desired,(height-row.block.getChildAt(0).measuredHeight-textHeight-dp(8)).coerceAtLeast(dp(48)))
            row.cards.forEach { card -> card.view.getChildAt(0).let { artwork -> if(artwork.layoutParams.height!=imageHeight) artwork.layoutParams=artwork.layoutParams.apply { height=imageHeight } } }
        }
    }
    fun firstId()=rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: refresh.id
}
