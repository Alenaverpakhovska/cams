package com.vladpen.cams

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import com.vladpen.*
import com.vladpen.Effects.edgeToEdge
import com.vladpen.cams.databinding.ActivityStreamsBinding


class StreamsActivity : AppCompatActivity(), Layout {
    private val binding by lazy { ActivityStreamsBinding.inflate(layoutInflater) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override lateinit var rootView: View
    override lateinit var layoutListener: ViewTreeObserver.OnGlobalLayoutListener
    override var insets: Insets? = null
    override var hideBars = false

    private var sourceId = -1
    private var isGroup: Boolean = false
    private var streams = listOf<Int>()

    private var fragments = arrayListOf<StreamFragment>()
    private val loadings = mutableMapOf<Int, Boolean>()

    private lateinit var gestureDetector: VideoGestureDetector
    private var gestureInProgress = 0
    private val watchdogInterval: Long = 10000 // milliseconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        edgeToEdge(binding.root) { innerPadding ->
            insets = innerPadding
        }
        sourceId = intent.getIntExtra("id", -1)
        val sourceType = intent.getStringExtra("type") ?: return
        isGroup = sourceType == "group"

        try { // prevents exception if settings file is corrupted
            initActivity(sourceType)
            if (savedInstanceState == null) {
                initFragments()
            }
            initLayout(binding.root)
            initMute()
        } catch (_: Exception) {
            Log.e("StreamActivity", "Data is corrupted ($sourceType $sourceId), redirect")
            startActivity(
                if (isGroup) {
                    Intent(this, GroupEditActivity::class.java).putExtra("groupId", sourceId)
                } else {
                    Intent(this, EditActivity::class.java).putExtra("streamId", sourceId)
                }
            )
        }
    }

    private fun initActivity(sourceType: String) {
        this.onBackPressedDispatcher.addCallback(callback)
        binding.toolbar.btnBack.setOnClickListener {
            back()
        }
        when (sourceType) {
            "stream" -> {
                val stream = StreamData.getById(sourceId) ?: return
                binding.toolbar.tvLabel.text = stream.name
                streams = listOf(sourceId)
            }
            "group" -> {
                val group = GroupData.getById(sourceId) ?: return
                binding.toolbar.tvLabel.text = group.name
                streams = group.streams
                GroupData.backGroupId = -1  // drop
            }
            else -> throw Exception("invalid source type")
        }
        gestureDetector = VideoGestureDetector(binding.rlStreamsBox)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Alert.init(this, binding.toolbar.btnAlert)
    }

    private fun initFragments() {
        var isChannelsAvailable = false
        for (id in streams) {
            if (id > StreamData.getAll().count())
                throw Exception("invalid group ID")

            val stream = StreamData.getById(id) ?: continue

            val frame = FrameLayout(this)
            frame.id = FrameLayout.generateViewId()
            binding.rlStreamsBox.addView(frame)

            val fragment = StreamFragment.newInstance(id)
            supportFragmentManager.beginTransaction().add(frame.id, fragment).commit()

            if (isGroup) {
                frame.setOnClickListener {
                    if (gestureInProgress <= 0) {
                        GroupData.backGroupId = sourceId  // save for back navigation
                        reload("stream", id)
                    }
                }
                frame.setOnLongClickListener {
                    initBars()
                    true
                }
            }
            fragments.add(fragment)

            isChannelsAvailable = isChannelsAvailable || stream.url2 != null

            loadings[id] = true
        }
        if (isChannelsAvailable) {
            initChannel()
        }
    }

    private fun initChannel() {
        var channel = StreamData.getChannel(isGroup)
        binding.btnChannel.setImageResource(Utils.getChannelButton(channel))
        binding.btnChannel.visibility = View.VISIBLE
        binding.btnChannel.setOnClickListener {
            channel = if (channel != 1) 1 else 0
            StreamData.setChannel(channel, isGroup)
            binding.btnChannel.setImageResource(Utils.getChannelButton(channel))
            startAll(false)
        }
    }

    private fun initMute() {
        if (isGroup) {
            return
        }
        setMute(StreamData.getMute())

        binding.btnMute.setOnClickListener {
            var mute = StreamData.getMute()
            mute = if (mute == 0) 1 else 0
            setMute(mute)
            fragments[0].play()
            StreamData.setMute(mute)
        }
    }

    private fun setMute(mute: Int) {
        if (mute == 1) {
            fragments[0].volume = 0
            binding.btnMute.setImageResource(R.drawable.ic_baseline_volume_off_24)
        } else {
            fragments[0].volume = 100
            binding.btnMute.setImageResource(R.drawable.ic_baseline_volume_on_24)
        }
    }

    fun showMute() {
        binding.btnMute.visibility = View.VISIBLE
    }

    fun hideLoading(streamId: Int) {
        loadings.remove(streamId)
        if (loadings.isEmpty())
            binding.progressBar.pbLoading.visibility = View.GONE
    }

    private val callback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            back()
        }
    }

    private fun back() {
        if (GroupData.backGroupId > -1) {
            reload("group", GroupData.backGroupId)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        startAll(true)
        handler.postDelayed(runnable, watchdogInterval)
    }

    override fun onStop() {
        super.onStop()
        for (f in fragments) {
            f.release()
        }
        handler.removeCallbacks(runnable)
    }

    private fun startAll(init: Boolean = false) {
        for (f in fragments) {
            if (init)
                f.initPlayer()
            f.start(Uri.parse(StreamData.getUrl(f.stream, isGroup)))
            loadings[f.streamId] = true
        }
        binding.progressBar.pbLoading.visibility = View.VISIBLE
    }

    private val runnable = object : Runnable {
        override fun run() {
            for (f in fragments) {
                if (f.watchdog())
                    continue
                binding.progressBar.pbLoading.visibility = View.VISIBLE
            }
            handler.postDelayed(this, watchdogInterval)
        }
    }

    override fun resizeLayout() {
        resizeGrid(fragments)
        gestureDetector.reset()
        initBars()
    }

    override fun dispatchTouchEvent(e: MotionEvent?): Boolean {
        if (e == null)
            return super.dispatchTouchEvent(null)

        val res = gestureDetector.onTouchEvent(e) || e.pointerCount > 1
        if (res)
            gestureInProgress = e.pointerCount + 1

        if (e.action == MotionEvent.ACTION_UP) {
            if (gestureInProgress == 0)
                initBars()
            else
                gestureInProgress -= 1

            for (f in fragments) {
                if (f.getFrame().getGlobalVisibleRect(Rect()))
                    f.play()
                else
                    f.stop()
            }
        }
        return res || super.dispatchTouchEvent(e)
    }

    private fun reload(type: String, id: Int) {
        if (type == "stream") {
            val i = streams.indexOf(id)
            Effects.dimmer(fragments[i].getFrame())
        }
        startActivity(
            Intent(this, StreamsActivity::class.java)
                .putExtra("type", type)
                .putExtra("id", id)
        )
    }

    private fun initBars() {
        Effects.cancel()
        if (!isGroup) {
            val stream = StreamData.getById(sourceId)
            if (stream != null && stream.sftp != null) {
                binding.toolbar.btnLink.visibility = View.VISIBLE
                binding.toolbar.btnLink.setOnClickListener {
                    filesScreen()
                }
            }
        }
        binding.toolbar.root.visibility = View.VISIBLE
        binding.llStreamsBar.visibility = View.VISIBLE
        if (hideBars) {
            Effects.delayedFadeOut(arrayOf(binding.toolbar.root, binding.llStreamsBar))
        }
    }

    private fun filesScreen() {
        val intent = Intent(this, FilesActivity::class.java)
            .putExtra("streamId", sourceId)
        startActivity(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initLayout(binding.root)
    }
}