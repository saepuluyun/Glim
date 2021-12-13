package chat.glim.mobile.ui.activity

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import chat.glim.mobile.R
import chat.glim.mobile.custom.StoriesProgressView
import chat.glim.mobile.data.model.entities.Status
import chat.glim.mobile.databinding.ActivityOtherStoriesBinding
import chat.glim.mobile.ui.fragment.StatusReplyDialogFragment
import chat.glim.mobile.utils.*
import chat.glim.mobile.viewmodel.StatusViewModel
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.koin.androidx.viewmodel.ext.android.viewModel

class ShowOtherStatusActivity : BaseActivity(), StoriesProgressView.StoriesListener {

    private lateinit var binding: ActivityOtherStoriesBinding
    private val viewModel: StatusViewModel by viewModel()
//    private var sheetBehavior: BottomSheetBehavior<RelativeLayout>? = null

    private lateinit var type: String
    private lateinit var statusModel: MutableList<Status>
    private lateinit var statusList: MutableList<Status>
    private lateinit var statusListContent: MutableList<Status>
    private lateinit var currentStatus: Status

    private var idStatus: Int = -1
    private var counter = 0
    private var transition = ""
    var pressTime = 0L
    var limit = 500L
    var x1: Float = 0.0f
    var x2: Float = 0.0f
    var y1: Float = 0.0f
    var y2: Float = 0.0f
    val minimumDistance = 250 //swipe distance

    private val onTouchListener: View.OnTouchListener = object : View.OnTouchListener {
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressTime = System.currentTimeMillis()
                    binding.stories!!.pause()

                    x1 = event.x
                    y1 = event.y

                    return false
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    if (!binding.videoStatus.isPlaying)
                        binding.stories!!.resume()

                    x2 = event.x
                    y2 = event.y

                    val valueX = x2 - x1
                    val valueY = y2 - y1

                    if (kotlin.math.abs(valueX) > minimumDistance)

                        if (x2 > x1) {
                            transition = "PREV"
                            x1 = 0F
                            x2 = 0F
                            //prev contact
                            var pos = statusList.indexOf(statusModel.first())

                            if (pos > 0) {
                                val status = statusList[pos - 1]
                                var statusData = ArrayList<Status>()
                                statusListContent.forEach {
                                    if (it.userID == status.userID) {
                                        if (type == "RECENT" && status.isViewed == 0L) {
                                            statusData.add(
                                                Status(
                                                    it.id,
                                                    it.caption,
                                                    it.content,
                                                    it.contentType,
                                                    it.fontStyle,
                                                    it.backgroundColor,
                                                    it.isViewed,
                                                    it.viewsCount,
                                                    it.userID,
                                                    it.createdAt,
                                                    it.senderId,
                                                    it.senderName,
                                                    it.senderPhone,
                                                    it.senderAvatar,
                                                    it.thumbnail,
                                                    it.countViewed,
                                                    it.mutedStatus,
                                                    it.numView,
                                                    it.duration
                                                )
                                            )
                                        } else if (type != "RECENT" && status.isViewed == 1L) {
                                            statusData.add(
                                                Status(
                                                    it.id,
                                                    it.caption,
                                                    it.content,
                                                    it.contentType,
                                                    it.fontStyle,
                                                    it.backgroundColor,
                                                    it.isViewed,
                                                    it.viewsCount,
                                                    it.userID,
                                                    it.createdAt,
                                                    it.senderId,
                                                    it.senderName,
                                                    it.senderPhone,
                                                    it.senderAvatar,
                                                    it.thumbnail,
                                                    it.countViewed,
                                                    it.mutedStatus,
                                                    it.numView,
                                                    it.duration
                                                )
                                            )
                                        }
                                    }
                                }
                                setStatus(statusData)
                            }
                        } else {
                            x1 = 0F
                            x2 = 0F
                            transition = "NEXT"
                            //next contact
                            val pos = statusList.indexOf(statusModel.first())
                            if (pos + 1 < statusList.size) {
                                val status = statusList[pos + 1]
                                var statusData = ArrayList<Status>()
                                statusListContent.forEach {
                                    if (it.userID == status.userID) {
                                        if (type == "RECENT" && status.isViewed == 0L) {
                                            statusData.add(
                                                Status(
                                                    it.id,
                                                    it.caption,
                                                    it.content,
                                                    it.contentType,
                                                    it.fontStyle,
                                                    it.backgroundColor,
                                                    it.isViewed,
                                                    it.viewsCount,
                                                    it.userID,
                                                    it.createdAt,
                                                    it.senderId,
                                                    it.senderName,
                                                    it.senderPhone,
                                                    it.senderAvatar,
                                                    it.thumbnail,
                                                    it.countViewed,
                                                    it.mutedStatus,
                                                    it.numView,
                                                    it.duration
                                                )
                                            )
                                        } else if (type != "RECENT" && status.isViewed == 1L) {
                                            statusData.add(
                                                Status(
                                                    it.id,
                                                    it.caption,
                                                    it.content,
                                                    it.contentType,
                                                    it.fontStyle,
                                                    it.backgroundColor,
                                                    it.isViewed,
                                                    it.viewsCount,
                                                    it.userID,
                                                    it.createdAt,
                                                    it.senderId,
                                                    it.senderName,
                                                    it.senderPhone,
                                                    it.senderAvatar,
                                                    it.thumbnail,
                                                    it.countViewed,
                                                    it.mutedStatus,
                                                    it.numView,
                                                    it.duration
                                                )
                                            )
                                        }
                                    }
                                }
                                setStatus(statusData)
                            }

                        }

                    return limit < now - pressTime
                }
            }
            return false
        }
    }

    fun setStatus(stat: ArrayList<Status>) {

//        binding.container.gone()
        crossfade()
        Handler().postDelayed({
            statusModel.clear()
            statusModel.addAll(stat)

            statusModel.sortByDescending { it.id }
            loadData()
        }, shortAnimationDuration.toLong())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityOtherStoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        type = intent.extras?.getString("type")!!
        statusModel = intent.extras?.getParcelableArrayList<Status>("data")!!
        statusList = intent.extras?.getParcelableArrayList<Status>("allData")!!
        statusListContent = intent.extras?.getParcelableArrayList<Status>("allDataContent")!!
        statusModel.sortBy { it.id }

        initLayoutAndListener()
        loadData()
    }

    private fun crossfade() {
        binding.container.apply {
            // Set the content view to 0% opacity but visible, so that it is visible
            // (but fully transparent) during the animation.
            alpha = 1f
            visibility = View.VISIBLE

            // Animate the content view to 100% opacity, and clear any animation
            // listener set on the view.
            animate()
                .alpha(0f)
                .setDuration(shortAnimationDuration.toLong())
                .withEndAction {
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate()
                        .alpha(1f)
                        .setDuration(shortAnimationDuration.toLong())
                }
        }
        // Animate the loading view to 0% opacity. After the animation ends,
        // set its visibility to GONE as an optimization step (it won't
        // participate in layout passes, etc.)

    }

    private fun initLayoutAndListener() {
        binding.reverse.setOnClickListener {
            binding.stories.reverse()
        }
        binding.skip.setOnClickListener {
            binding.stories.skip()
        }

        binding.videoStatus.setOnPreparedListener {
            if (!binding.videoStatus.isPlaying) {
                binding.videoStatus.start()
                binding.stories.resume()
            }
        }

        binding.videoStatus.setOnCompletionListener {
            //write your code after complete video play
        }

        binding.skip.setOnTouchListener(onTouchListener)
        binding.reverse.setOnTouchListener(onTouchListener)

        binding.ivBack.setOnClickListener {
            onBackPressed()
        }

//        sheetBehavior = BottomSheetBehavior.from(binding.layBottomBar)
//        sheetBehavior?.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
//            override fun onStateChanged(view: View, newState: Int) {
//                when (newState) {
//                    BottomSheetBehavior.STATE_EXPANDED -> {
//                        sheetBehavior!!.isDraggable = true
//                        binding.stories.pause()
//                        binding.layTouch.gone()
//
//                        supportFragmentManager.let {
//                            val bundle = Bundle()
//                            bundle.putParcelable(Constant.CURRENT_STATUS, currentStatus)
//                            StatusReplyDialogFragment.newInstance(bundle).apply {
//                                show(it, tag)
//                            }
//                        }
//                    }
//                    BottomSheetBehavior.STATE_COLLAPSED -> {
//                        sheetBehavior!!.isDraggable = true
//                        if (!binding.videoStatus.isPlaying)
//                            binding.stories.resume()
//                        binding.layTouch.visible()
//                    }
//                    BottomSheetBehavior.STATE_DRAGGING -> {
//                        binding.stories.pause()
//                    }
//                    else -> { }
//                }
//            }
//
//            override fun onSlide(view: View, v: Float) {
//                binding.layBottomBar.alpha = 1.0f - v
//            }
//        })
    }

//    fun onReplyDialogDismiss(replySent: Boolean) {
//        sheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
//        if (replySent) {
//            Toast.makeText(applicationContext, getString(R.string.sending_reply), Toast.LENGTH_SHORT).show()
//            onBackPressed()
//        }
//    }

    private fun loadData() {
        counter = 0
        clearVideoView(binding.videoStatus)

        //list duration
        val durations = ArrayList<Long>()
        statusModel.forEach {
            if (it.duration > 0)
                durations.add((it.duration.toLong()*1000)+shortAnimationDuration.toLong())
            else
                durations.add(3000L)
        }

//        binding.stories.setStoriesCount(statusModel.size)
//        binding.stories.setStoryDuration(3000L)
        binding.stories.setStoriesCountWithDurations(durations.toLongArray())
        binding.stories.setStoriesListener(this)
        binding.stories.startStories(counter)

        binding.tvName.text = statusModel[counter].senderName
        Glide.with(applicationContext)
            .load(statusModel[counter].senderAvatar?.checkAvatar())
            .into(binding.photoImageView)
        binding.tvTime.text = statusModel[counter].createdAt?.getDateWithServerTimeStamp()
        binding.tvTextStatus.text = statusModel[counter].caption

        idStatus = statusModel[counter].userID.toInt()
        currentStatus = statusModel[counter]

        if (statusModel[counter].contentType == "media") {
            if (statusModel[counter].content!!.subSequence(
                    statusModel[counter].content!!.length - 3,
                    statusModel[counter].content!!.length
                ) == "mp4" ||
                statusModel[counter].content!!.subSequence(
                    statusModel[counter].content!!.length - 3,
                    statusModel[counter].content!!.length
                ) == "mkv"
            ) {
                binding.videoStatus.setVideoPath(statusModel[counter].content)
                binding.videoStatus.visible()
                binding.image.gone()
                binding.textStatus.gone()

                binding.container.setBackgroundColor(Color.parseColor("#999999"))

                Handler().postDelayed({
                    binding.stories.pause()
                }, 50)
            } else {
                Glide.with(applicationContext)
                    .load(statusModel[counter].content?.checkAvatar())
                    .thumbnail(Glide.with(applicationContext).load(statusModel[counter].thumbnail))
                    .into(binding.image)

                binding.videoStatus.gone()
                binding.image.visible()
                binding.textStatus.gone()
            }
        } else {
            binding.textStatus.text = statusModel[counter].content
            binding.textStatus.visible()
            binding.image.gone()
            binding.videoStatus.gone()
            binding.container.setBackgroundColor(Color.parseColor(statusModel[counter].backgroundColor))
            binding.textStatus.typeface = ResourcesCompat.getFont(
                this,
                resources.getIdentifier(
                    statusModel[counter].fontStyle?.toLowerCase()?.replace(" ", "_"),
                    "font",
                    packageName
                )
            )
        }

        if (statusModel[counter].isViewed == 0L)
            viewModel.updateStatus(statusModel[counter].id)
    }

    override fun onNext() {
        clearVideoView(binding.videoStatus)

        val countNext = ++counter
        if (countNext > (statusModel.size - 1)) return
        if (countNext < 0) return

        binding.tvTextStatus.text = statusModel[countNext].caption
        currentStatus = statusModel[countNext]

        if (statusModel[countNext].contentType == "media") {
            if (statusModel[countNext].content!!.subSequence(
                    statusModel[countNext].content!!.length - 3,
                    statusModel[countNext].content!!.length
                ) == "mp4" ||
                statusModel[countNext].content!!.subSequence(
                    statusModel[countNext].content!!.length - 3,
                    statusModel[countNext].content!!.length
                ) == "mkv"
            ) {
                binding.videoStatus.setVideoPath(statusModel[countNext].content)
                binding.videoStatus.visible()
                binding.image.gone()
                binding.textStatus.gone()

                binding.container.setBackgroundColor(Color.parseColor("#999999"))

                Handler().postDelayed({
                    binding.stories.pause()
                }, 50)
            } else {
                Glide.with(applicationContext)
                    .load(statusModel[countNext].content?.checkAvatar())
                    .thumbnail(Glide.with(applicationContext).load(statusModel[countNext].thumbnail))
                    .into(binding.image)
                binding.tvTime.text = statusModel[countNext].createdAt?.getDateWithServerTimeStamp()

                binding.image.visible()
                binding.videoStatus.gone()
                binding.textStatus.gone()
            }
        } else {
            binding.textStatus.text = statusModel[countNext].content
            binding.textStatus.visible()
            binding.container.setBackgroundColor(Color.parseColor(statusModel[countNext].backgroundColor))
            binding.image.gone()
            binding.videoStatus.gone()
            binding.textStatus.typeface = ResourcesCompat.getFont(
                this,
                resources.getIdentifier(
                    statusModel[countNext].fontStyle?.toLowerCase()?.replace(" ", "_"),
                    "font",
                    packageName
                )
            )
        }

        if (statusModel[countNext].isViewed == 0L)
            viewModel.updateStatus(statusModel[countNext].id)
    }

    override fun onPrev() {
        clearVideoView(binding.videoStatus)

        val countPrev = --counter
        if (countPrev < 0) return

        binding.tvTextStatus.text = statusModel[countPrev].caption
        currentStatus = statusModel[countPrev]

        if (statusModel[countPrev].contentType == "media") {
            if (statusModel[countPrev].content!!.subSequence(
                    statusModel[countPrev].content!!.length - 3,
                    statusModel[countPrev].content!!.length
                ) == "mp4" ||
                statusModel[countPrev].content!!.subSequence(
                    statusModel[countPrev].content!!.length - 3,
                    statusModel[countPrev].content!!.length
                ) == "mkv"
            ) {
                binding.videoStatus.setVideoPath(statusModel[countPrev].content)
                binding.videoStatus.visible()
                binding.image.gone()
                binding.textStatus.gone()

                binding.container.setBackgroundColor(Color.parseColor("#999999"))

                Handler().postDelayed({
                    binding.stories.pause()
                }, 50)
            } else {
                Glide.with(applicationContext)
                    .load(statusModel[countPrev].content?.checkAvatar())
                    .thumbnail(Glide.with(applicationContext).load(statusModel[countPrev].thumbnail))
                    .into(binding.image)
                binding.tvTime.text = statusModel[countPrev].createdAt?.getDateWithServerTimeStamp()

                binding.image.visible()
                binding.videoStatus.gone()
                binding.textStatus.gone()
            }
        } else {
            binding.textStatus.text = statusModel[countPrev].content
            binding.textStatus.visible()
            binding.container.setBackgroundColor(Color.parseColor(statusModel[countPrev].backgroundColor))
            binding.image.gone()
            binding.videoStatus.gone()
            binding.textStatus.typeface = ResourcesCompat.getFont(
                this,
                resources.getIdentifier(
                    statusModel[countPrev].fontStyle?.toLowerCase()?.replace(" ", "_"),
                    "font",
                    packageName
                )
            )
        }
    }

    override fun onComplete() {
        onBackPressed()
    }


    override fun onDestroy() {
        viewModel.onClear()
        binding.stories.destroy()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

//    override fun onBackPressed() {
//        if (sheetBehavior?.state == BottomSheetBehavior.STATE_EXPANDED) {
//            sheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
//            return
//        }
//
//        super.onBackPressed()
//    }

}