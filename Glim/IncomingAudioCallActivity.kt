package chat.glim.mobile.ui.activity

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.MediaPlayer
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import chat.glim.mobile.R
import chat.glim.mobile.data.model.CallInvitationType
import chat.glim.mobile.data.model.CallParticipantModel
import chat.glim.mobile.data.model.CallState
import chat.glim.mobile.databinding.ActivityIncomingAudioCallBinding
import chat.glim.mobile.services.GlimFirebaseMessagingService
import chat.glim.mobile.services.IncomingAudioCallService
import chat.glim.mobile.utils.Constant
import chat.glim.mobile.utils.Global
import chat.glim.mobile.utils.formatSecondsTime
import chat.glim.mobile.utils.removeViewFromParent
import chat.glim.mobile.viewmodel.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.ncorti.slidetoact.SlideToActView
import io.agora.rtc.ss.ScreenSharingClient
import io.agora.rtc.video.VideoEncoderConfiguration
import jp.wasabeef.glide.transformations.BlurTransformation
import org.koin.androidx.viewmodel.ext.android.viewModel


class IncomingAudioCallActivity : BaseActivity(), SlideToActView.OnSlideCompleteListener {

    private lateinit var binding: ActivityIncomingAudioCallBinding
    private val viewModel : IncomingAudioCallViewModel by viewModel()

    private var mMediaPlayer: MediaPlayer? = null
    private var currentDuration = 0
    private var localStreamView:SurfaceView? = null
    private var localScreenShareStreamView:SurfaceView? = null
    private var answerArrow = arrayOf("       ▸    ", "       ▸ ▸  ", "       ▸ ▸ ▸")
    private var answerArrowAmount = 0
    private var loadingHandler = Handler(Looper.getMainLooper())
    private var vibrator:Vibrator? = null
    private var switchCallDialog:Dialog? = null


    private var isSharing:Boolean = false
    private var viewOnTap:Boolean = false
    private var handler = Handler()
    private lateinit var mSSClient: ScreenSharingClient

    private val mListener: ScreenSharingClient.IStateListener = object : ScreenSharingClient.IStateListener {
        override fun onError(error: Int) {
            Log.e("Screenshare", "Screen share service error happened: $error")
        }

        override fun onTokenWillExpire() {
            Log.d("Screenshare", "Screen share service token will expire")
        }
    }

    private val callTimer = object: CountDownTimer(Constant.CALL_TIMEOUT_SEC * 1000, 1000) {
        override fun onTick(millisUntilFinished: Long) {}

        override fun onFinish() {
            //call not answered,finish early
            shouldFinishCall()
        }
    }

    val broadCastReceiver = object : BroadcastReceiver() {
        override fun onReceive(contxt: Context?, intent: Intent?) {
            if(intent?.action == GlimFirebaseMessagingService.SWITCH_CALL_BROADCAST){
                when(intent.getStringExtra("ACTION")){
                    GlimFirebaseMessagingService.SWITCH_CALL_ACCEPT_REQUEST_KEY -> {
                        switchCallDialog?.dismiss()
                        switchCallDialog = null
                        viewModel.switchCall()
                    }

                    GlimFirebaseMessagingService.SWITCH_CALL_REJECT_REQUEST_KEY -> {
                        switchCallDialog?.dismiss()
                        switchCallDialog = null
                        switchCallDialog = showMessageDialog(getString(R.string.call_change_denied))
                    }

                    GlimFirebaseMessagingService.SWITCH_CALL_CANCEL_REQUEST_KEY -> {
                        switchCallDialog?.dismiss()
                        switchCallDialog = null
                        switchCallDialog = showMessageDialog(getString(R.string.call_change_cancelled))
                    }

                    GlimFirebaseMessagingService.SWITCH_CALL_REQUEST_KEY -> {
                        try {
                            val target = viewModel.getActiveParticipants(viewModel.currentUser?.id
                                    ?: 0).first()
                            target.let {
                                switchCallDialog?.dismiss()
                                switchCallDialog = null
                                switchCallDialog = showConfirmationDialog(getString(R.string.call_change_ask, target.name), getString(R.string.call_change_ask_deny_button), getString(R.string.call_change_ask_accept_button), {
                                    switchCallDialog = null
                                    viewModel.rejectSwitchCall()
                                }, {
                                    switchCallDialog = null
                                    viewModel.acceptSwitchCall()
                                })
                            }
                        } catch (e: NoSuchElementException) {

                        }

                    }
                }
            }
        }
    }

    fun shouldFinishCall(){
        val myState = viewModel.getMyState()
        if(myState == CallState.ONGOING){
            viewModel.rejectRinggingParticipants()
        }else {
            viewModel.sendRejectSignal()
        }
        Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Global.userCallState = Constant.USER_CALL_STATE_UNAVAILABLE_REASON_ON_ANOTHER_CALL
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingAudioCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if(havePermissions()) {
            initActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        mMediaPlayer?.start()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadCastReceiver, IntentFilter(GlimFirebaseMessagingService.SWITCH_CALL_BROADCAST))
    }

    override fun onPause() {
        super.onPause()

        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(broadCastReceiver)
    }

    override fun onDestroy() {
        val myState = viewModel.getMyState()
        if(myState == CallState.ONGOING){
            viewModel.sendEndSignal()
        }else {
            viewModel.sendRejectSignal()
        }
        viewModel.onClear()
        viewModel.clearRTC()
        Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
        stopRingtoneAudio()
        stopRingtoneVibrate()
        cleanMediaPlayer()
        applicationContext.stopService(Intent(this, IncomingAudioCallService::class.java))

        if(isSharing) {
            mSSClient.stop(this)
        }
        super.onDestroy()
    }

    private fun initActivity(){
        initView()
        initObserver()

        try {
            val agoraToken = intent.getStringExtra(GlimFirebaseMessagingService.PUSH_NOTIF_AGORA_TOKEN_KEY) ?: ""
            val agoraChannel = intent.getStringExtra(GlimFirebaseMessagingService.PUSH_NOTIF_AGORA_CHANNEL_KEY) ?: ""
            val invitationType = CallInvitationType.fromInt((intent.getStringExtra(GlimFirebaseMessagingService.PUSH_NOTIF_CALL_TYPE)
                    ?: "0").toInt())

            val callerId = intent.getIntExtra(GlimFirebaseMessagingService.PUSH_NOTIF_CALL_SENDER_ID_KEY, 0)
            val callerAvatar = intent.getStringExtra(GlimFirebaseMessagingService.PUSH_NOTIF_CALL_SENDER_AVATAR_KEY) ?: Constant.DEFAULT_AVATAR
            val callerName = intent.getStringExtra(GlimFirebaseMessagingService.PUSH_NOTIF_CALL_SENDER_NAME_KEY) ?: ""
            val callerIsUnknownContact = intent.getIntExtra(GlimFirebaseMessagingService.PUSH_NOTIF_CALL_SENDER_ID_KEY, 0)

            val callParticipantsJSON = intent.getStringExtra(GlimFirebaseMessagingService.PUSH_NOTIF_CALL_PARTICIPANT)  ?: ""

            if(agoraToken.isEmpty() || agoraChannel.isEmpty() || callParticipantsJSON.isEmpty()){
                Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                finish()
            }else {
                val mainCaller =
                    CallParticipantModel(callerId, callerName, callerAvatar, "", callerIsUnknownContact)
                val callParticipant = CallParticipantModel.fromJson(callParticipantsJSON)

                val allParticipants = ArrayList<CallParticipantModel>()
                allParticipants.add(mainCaller)
                allParticipants.addAll(callParticipant)
                viewModel.initialize(callerId, invitationType, allParticipants, agoraToken, agoraChannel)
            }

        }catch (e: Exception){
            Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
            finish()
        }
        initScreenShare()
    }

    private fun initScreenShare(){
        mSSClient = ScreenSharingClient.getInstance()
        mSSClient.setListener(mListener)
    }

    private fun initView() {
        window?.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window?.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

        updateControlUI()

        binding.videoCallButton.setOnClickListener {
            viewModel.requestSwitchCall()
        }

        binding.answerButton.setOnClickListener {
            val myState = viewModel.getMyState()
            if(myState == CallState.RINGING) {
                applicationContext.stopService(Intent(this, IncomingAudioCallService::class.java))
                stopRingtoneAudio()
                stopRingtoneVibrate()
                viewModel.sendAcceptSignal()
            }
        }

        binding.declineButton.setOnClickListener {

            val myState = viewModel.getMyState()
            if(myState == CallState.ONGOING){
                viewModel.sendEndSignal()
            }else {
                viewModel.sendRejectSignal()
            }
        }

        binding.addContactButton.setOnClickListener {
            val intent = Intent(this, AddCallActivity::class.java)
            intent.putIntegerArrayListExtra(
                    AddCallActivity.PARAM_EXCLUDED_CONTACT_IDS,
                    ArrayList(viewModel.currentParticipants.filter {
                        val state = viewModel.currentCallLiveData?.getStateFor(it.id)
                        state == CallState.ONGOING || state == CallState.RINGING || state == CallState.READY
                    }.map { it.id })
            )
            startActivityForResult(intent, REQUEST_ADD_CALL_PARTICIPANT)

        }

        binding.speakerButton.setOnClickListener{
            viewModel.toggleSpeakerActive()
        }

        binding.muteButton.setOnClickListener{
            viewModel.toggleMicMuted()
        }

        binding.switchCameraButton.setOnClickListener{
            viewModel.toggleFrontCamera()
        }


        binding.muteCamVideoButton.setOnClickListener{
            viewModel.toggleCameraMuted()
        }

        binding.muteMicVideoButton.setOnClickListener{
            viewModel.toggleMicMuted()
        }

        binding.shareScreenButton.setOnClickListener {
            if(!isSharing) {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
                    switchToSchareScreen()
                    isSharing = true

                    binding.shareScreenButton.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(
                                    this,
                                    R.color.primaryRed
                            )
                    )
                    binding.shareScreenButton.iconTint = ColorStateList.valueOf(
                            ContextCompat.getColor(
                                    this,
                                    (R.color.white)
                            )
                    )
                }else{
                    AlertDialog.Builder(this).setTitle("").setMessage(getString(R.string.screen_share_not_supported))
                        .setPositiveButton(getString(R.string.ok)
                        ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
                        .show()
                }
            }else{
                mSSClient.stop(this)
                isSharing = false

                binding.shareScreenButton.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                                this,
                                R.color.white
                        )
                )
                binding.shareScreenButton.iconTint = ColorStateList.valueOf(
                        ContextCompat.getColor(
                                this,
                                (R.color.black)
                        )
                )
            }
        }

        binding.slideAnswerButton.onSlideCompleteListener = this



        loadingHandler.post(object : Runnable {
            override fun run() {
                loadingHandler.postDelayed(this, 500)
                binding.slideAnswerButton.text = answerArrow[answerArrowAmount % 3]
                answerArrowAmount += 1
            }
        })
    }

    private fun initRinggingUI(participants: List<CallParticipantModel>){
        if(participants.size == 1){
            val target = participants.first()

            binding.participantGrid.visibility = View.GONE
            load1On1AudioUI(target)
            binding.nameTextView.text = target.name
            binding.timerTextView.text = if(viewModel.isVideoCall()) getString(R.string.incoming_video_calling_personal) else getString(R.string.incoming_calling_personal)
        }else{
            //group call
            binding.participantGrid.visibility = View.VISIBLE
            binding.photoImageView.visibility = View.GONE
            binding.backgroundImageView.visibility = View.GONE
            binding.localVideoViewContainer.visibility = View.GONE
            binding.remoteVideoViewContainer.visibility = View.GONE

            val participantCount = viewModel.currentParticipants.size
            var counter = 1
            for (view in binding.participantGrid.children) {
                val lp = GridLayout.LayoutParams(view.layoutParams)

                //Set Show Hide
                if (counter <= participantCount) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)

                    Glide.with(applicationContext)
                        .load(viewModel.currentParticipants[counter - 1].avatar)
                        .placeholder(R.color.greyPhotoBorder)
                        .error(R.color.greyPhotoBorder)
                        .into((view as RelativeLayout).children.first() as ImageView)
                } else {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)

                }

                //set Last Row if participant have odds amount
                if (counter == participantCount && counter % 2 == 1) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 2, 1f)
                }
                view.layoutParams = lp
                counter += 1
            }
        }


        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                startRingtoneAudio()
                callTimer.start()
            }
            AudioManager.RINGER_MODE_SILENT -> {
                callTimer.start()
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                startRingtoneVibrate()
                callTimer.start()
            }
        }
    }

    private fun initObserver() {
        viewModel.loading.observe(this, {
            it?.let {
                if (it) showLoading() else hideLoading()
            }
        })

        viewModel.durationTicker.observe(this, {
            it?.let {
                currentDuration = it
                updateDurationUI()
            }
        })

        viewModel.isCameraMuted.observe(this, { isSpeakerActive ->
            if (isSpeakerActive) {
                binding.muteCamVideoButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaryRed))
                binding.muteCamVideoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, (R.color.white)))
                binding.localVideoViewContainer.visibility = View.GONE
            } else {
                binding.muteCamVideoButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, (R.color.white)))
                binding.muteCamVideoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, (R.color.black)))
                binding.localVideoViewContainer.visibility = View.VISIBLE
            }
        })

        viewModel.isSpeakerActive.observe(this, { isSpeakerActive ->
            if (isSpeakerActive) {
                binding.speakerButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaryBlue))
                binding.speakerButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, (R.color.white)))
            } else {
                binding.speakerButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, (R.color.white)))
                binding.speakerButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, (R.color.black)))
            }
        })

        viewModel.isMicMuted.observe(this, { isSelfMuted ->
            Log.d("AGORA-AUDIO", "Self is muted $isSelfMuted")
            if (isSelfMuted) {
                binding.muteButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaryRed))
                binding.muteButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
                binding.muteMicVideoButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaryRed))
                binding.muteMicVideoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
            } else {
                binding.muteButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
                binding.muteButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
                binding.muteMicVideoButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
                binding.muteMicVideoButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
            }
        })

        viewModel.isRemoteCameraMuted.observe(this, { isMuted ->
            if (isMuted) {
                binding.remoteVideoViewContainer.visibility = View.GONE
                binding.backgroundImageView.visibility = View.VISIBLE
            } else {
                binding.remoteVideoViewContainer.visibility = View.VISIBLE
                binding.backgroundImageView.visibility = View.GONE
            }
        })

        viewModel.state.observe(this, {
            when (it) {

                is IncomingCallLocalVideoStreamReady -> {
                    localStreamView = it.view
                }
                is IncomingCallRemoteVideoStreamReady -> {
                    Global.userCallState = Constant.USER_CALL_STATE_UNAVAILABLE_REASON_ON_ANOTHER_CALL
                    Log.d("refreshCallStateUI ", "IncomingCallRemoteVideoStreamReady")
                    refreshCallStateUI()
                }
                is IncomingLocalScreenShareVideoStreamReady -> {
                    localScreenShareStreamView = it.view
                    refreshCallStateUI()
                }
                is IncomingLocalScreenShareVideoStreamEnded -> {
                    localScreenShareStreamView = null
                    refreshCallStateUI()
                }
                is IncomingAudioCallUserReady -> {
                    Global.userCallState = Constant.USER_CALL_STATE_UNAVAILABLE_REASON_ON_ANOTHER_CALL
                    viewModel.initCall()
                    initRinggingUI(it.participants)
                }
                is IncomingCallUpdated -> {
                    refreshCallStateUI()
                    if (viewModel.shouldEndCall(it.data)) {
                        viewModel.leaveChannel()
                    }

                    val participantIds = viewModel.currentCallLiveData?.getTargetIdByState(viewModel.currentUser!!.id.toString(), CallState.RINGING)

                    if (participantIds!!.isNotEmpty()) {
                        callTimer.cancel()
                        callTimer.start()
                    }

                    val myState = viewModel.getMyState()
                    if (myState == CallState.ONGOING) {
                        if (viewModel.isVideoCall()) {
                            if (viewOnTap) {
                                viewOnTap = false
                                onHiddenControl()
                                handler.removeCallbacksAndMessages(null)
                            } else {
                                viewOnTap = true
                                onShowControl()
                                handler.postDelayed({
                                    viewOnTap = false
                                    onHiddenControl()
                                    handler.removeCallbacksAndMessages(null)
                                }, 3000)
                            }
                        }
                    }
                }

                is IncomingCallAddParticipantFailed -> {
                    val message =
                            if (it.errMsg.isEmpty()) getString(R.string.call_add_participant_failed) else it.errMsg
                    showErrorDialog(message) {
                    }

                }

                is IncomingAudioCallErrorTerminated -> {
                    val message =
                            if (it.errMsg.isEmpty()) getString(R.string.call_incoming_failed) else it.errMsg
                    showErrorDialog(message) {
                        viewModel.leaveChannel()
                    }

                }

                is IncomingCallEnded -> {
                    Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                    finish()
                }

                is IncomingCallErrorNetworkTerminated -> {
                    Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                    val message =
                            if (it.errMsg.isEmpty()) getString(R.string.call_network_interrupted) else it.errMsg
                    showErrorDialog(message) {
                        viewModel.leaveChannel()
                    }
                }

                is IncomingCallSwitchSuccess -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                    switchCallDialog = showMessageDialog(getString(R.string.call_change_wait_approval), getString(R.string.call_change_cancel_button)) {
                        viewModel.cancelSwitchCall()
                    }
                }

                is IncomingCallSwitchFailed -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                    val message =
                            if (it.errMsg.isEmpty()) getString(R.string.call_change_failed) else it.errMsg
                    switchCallDialog = showErrorDialog(message) {

                    }

                }

                is IncomingCallSwitchAcceptSuccess -> {
                    viewModel.switchCall()
                }

                is IncomingCallSwitchRejectSuccess -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                }

                is IncomingCallSwitchCancelSuccess -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                }

                is IncomingCallSwitchCancelFailed -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                    val message =
                            if (it.errMsg.isEmpty()) getString(R.string.call_change_failed) else it.errMsg
                    switchCallDialog = showErrorDialog(message) {
                        switchCallDialog = null
                        switchCallDialog = showMessageDialog(getString(R.string.call_change_wait_approval), getString(R.string.call_change_cancel_button)) {
                            viewModel.cancelSwitchCall()
                        }
                    }
                }

                else -> {
                }
            }
        })
    }

    private fun switchToSchareScreen(){
        if (!isSharing) {
            mSSClient.start(
                    this,
                    resources.getString(R.string.agora_app_id),
                    viewModel.agoraToken,
                    viewModel.agoraChannel,
                    viewModel.myId + 1000000, //negative id indicates share screen stream
                    VideoEncoderConfiguration(
                            getScreenDimensions(),
                            VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                            VideoEncoderConfiguration.STANDARD_BITRATE,
                            VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                    )
            )
            isSharing = true
        } else {
            mSSClient.stop(this)
            isSharing = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQ_ID_CALL -> {
                if (grantResults.size == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    initActivity()
                } else {
                    showErrorDialog(getString(R.string.calling_personal_failed_permission)) {
                        viewModel.leaveChannel()
                    }
                }
            }
        }
    }

    private fun havePermissions(): Boolean {
        //check for camera & mic permission
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this,
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                    PERMISSION_REQ_ID_CALL
            )
            return false
        }
        return true
    }

    private fun refreshCallStateUI(){
        Log.d("refreshCallStateUI ", "Original part " + viewModel.currentParticipants.size)
        //filter inactive users & self
        val activeParticipant = viewModel.currentParticipants.filter {
            val participantState = viewModel.currentCallLiveData!!.getStateFor(it.id)

            Log.d("refreshCallStateUI ", "Filtering " + it.id + " " + it.name + " " + participantState.toString())
            (participantState == CallState.ONGOING || participantState == CallState.RINGING || participantState == CallState.READY)
        }
        Log.d("refreshCallStateUI ", "Filtered part " + activeParticipant.size)
        if(activeParticipant.size <= 2){
            binding.participantGrid.visibility = View.GONE

            val target = viewModel.currentParticipants.find {
                val participantState = viewModel.currentCallLiveData!!.getStateFor(it.id)
                it.id != viewModel.myId && (participantState == CallState.ONGOING || participantState == CallState.RINGING || participantState == CallState.READY)
            }
            if(target != null) {
                binding.nameTextView.text = target.name

                val targetState = viewModel.currentCallLiveData?.getStateFor(target.id)
                if(targetState == CallState.RINGING){
                    //if ringging, show name, profile picture & blurred background
                    binding.timerTextView.text = if(viewModel.isVideoCall()) getString(R.string.incoming_video_calling_personal) else getString(R.string.incoming_calling_personal)
                    load1On1AudioUI(target)
                }else if(targetState == CallState.ONGOING){
                    Log.d("refreshCallStateUI ", "ONGOING " + viewModel.isVideoCall() + " " + (target.videoView != null))
                    if(viewModel.isVideoCall()){
                        load1On1VideoUI(target)
                    }else{
                        load1On1AudioUI(target)
                    }
                }else{
                    binding.timerTextView.text = ""
                }

            }
        }else{
            //group call
            binding.participantGrid.visibility = View.VISIBLE
            binding.photoImageView.visibility = View.GONE
            binding.backgroundImageView.visibility = View.GONE
            binding.localVideoViewContainer.visibility = View.GONE
            binding.remoteVideoViewContainer.visibility = View.GONE


            //group call
            val participantCount = activeParticipant.size
            var counter = 1
            for (view in binding.participantGrid.children) {
                val lp = GridLayout.LayoutParams(view.layoutParams)

                //Set Show Hide
                if (counter <= participantCount) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    val participant = activeParticipant[counter - 1]
                    val statusTextView = (view as RelativeLayout).children.elementAt(1) as? TextView
                    if(statusTextView != null && viewModel.currentCallLiveData != null){
                        val participantState = viewModel.currentCallLiveData!!.getStateFor(participant.id)
                        if(participant.id == viewModel.myId){
                            if(viewModel.isVideoCall()){
                                if(participant.id == viewModel.myId && localStreamView != null){
                                    if(isSharing && localScreenShareStreamView != null){
                                        removeViewFromParent(localScreenShareStreamView!!)
                                        view.addView(localScreenShareStreamView)
                                    }else {
                                        removeViewFromParent(localStreamView!!)
                                        view.addView(localStreamView)
                                    }
                                    statusTextView.text = ""
                                }else{
                                    statusTextView.text = getString(R.string.call_name_self)
                                }
                            }else{
                                Glide.with(applicationContext)
                                    .load(viewModel.currentParticipants[counter - 1].avatar)
                                    .placeholder(R.color.greyPhotoBorder)
                                    .error(R.color.greyPhotoBorder)
                                    .into(view.children.first() as ImageView)
                            }
                        }else {
                            Glide.with(applicationContext)
                                .load(viewModel.currentParticipants[counter - 1].avatar)
                                .placeholder(R.color.greyPhotoBorder)
                                .error(R.color.greyPhotoBorder)
                                .into(view.children.first() as ImageView)
                            if (participantState == CallState.RINGING) {
                                statusTextView.text = getString(R.string.calling_personal)
                            } else if (participantState == CallState.REJECTED) {
                                statusTextView.text = getString(R.string.call_rejected)
                            } else if (participantState == CallState.UNANSWERED) {
                                statusTextView.text = getString(R.string.call_unanswered)
                            } else if (participantState == CallState.READY || participantState == CallState.ONGOING) {
                                if(viewModel.isVideoCall() && participant.videoView != null){
                                    if(participant.isScreenSharing && participant.screenShareVideoView != null){
                                        removeViewFromParent(participant.screenShareVideoView!!)
                                        view.addView(participant.screenShareVideoView)
                                    }else {
                                        removeViewFromParent(participant.videoView!!)
                                        view.addView(participant.videoView)
                                    }

                                    statusTextView.text = ""
                                }else{
                                    statusTextView.text = participant.name
                                }
                            } else {
                                statusTextView.text = ""
                            }
                        }
                    }
                } else {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)

                }

                //set Last Row if participant have odds amount
                if (counter == participantCount && counter % 2 == 1) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 2, 1f)
                }
                view.layoutParams = lp
                counter += 1
            }
        }
        updateControlUI()
    }

    private fun updateDurationUI(){
        if(viewModel.getMyState() == CallState.ONGOING || viewModel.getMyState() == CallState.READY){
            binding.nameTextView.visibility = View.VISIBLE
            binding.timerTextView.visibility = View.VISIBLE
            binding.answerButton.visibility = View.GONE
            binding.slideAnswerButton.visibility = View.GONE
            binding.timerTextView.text = currentDuration.formatSecondsTime()
            updateControlUI()
        }
    }

    private fun startRingtoneVibrate(){
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            //deprecated in API 26
            vibrator?.vibrate(5000)
        }
    }

    private fun stopRingtoneVibrate(){
        vibrator?.cancel()
    }

    private fun startRingtoneAudio(){
        cleanMediaPlayer()
        mMediaPlayer = MediaPlayer.create(this, Settings.System.DEFAULT_RINGTONE_URI)

        val audioAttribute = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        mMediaPlayer?.setAudioAttributes(audioAttribute)
        mMediaPlayer?.isLooping = true
        mMediaPlayer?.start()
    }

    private fun stopRingtoneAudio(){
        mMediaPlayer?.stop()
    }

    private fun cleanMediaPlayer(){
        mMediaPlayer?.stop()
        mMediaPlayer?.reset()
        mMediaPlayer?.release()
        mMediaPlayer = null
    }

    private fun load1On1AudioUI(target: CallParticipantModel){
        binding.photoImageView.visibility = View.VISIBLE
        binding.backgroundImageView.visibility = View.VISIBLE

        binding.localVideoViewContainer.visibility = View.GONE
        binding.remoteVideoViewContainer.visibility = View.GONE

        Glide.with(applicationContext)
            .load(target.avatar)
            .placeholder(R.color.greyPhotoBorder)
            .error(R.color.greyPhotoBorder)
            .apply(RequestOptions.bitmapTransform(BlurTransformation(44)))
            .into(binding.backgroundImageView)

        Glide.with(applicationContext)
            .load(target.avatar)
            .placeholder(R.color.greyPhotoBorder)
            .error(R.color.greyPhotoBorder)
            .into(binding.photoImageView)
    }

    private fun load1On1VideoUI(target: CallParticipantModel){
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.nameTextView.textSize = 16F
        binding.timerTextView.textSize = 10F

        binding.photoImageView.visibility = View.GONE
        binding.backgroundImageView.visibility = View.GONE

        binding.localVideoViewContainer.visibility = View.VISIBLE
        binding.remoteVideoViewContainer.visibility = View.VISIBLE


        if(target.videoView != null) {
            if(target.isScreenSharing && target.screenShareVideoView != null) {
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading remote screenshare" + target.id)
                removeViewFromParent(target.screenShareVideoView!!)
                binding.remoteVideoViewContainer.addView(target.screenShareVideoView)
            }else{
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading remote camera " + target.id)
                removeViewFromParent(target.videoView!!)
                binding.remoteVideoViewContainer.addView(target.videoView)
            }
        }

        if(localStreamView != null) {
            if(isSharing && localScreenShareStreamView != null) {
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading local screenshare")
                removeViewFromParent(localScreenShareStreamView!!)
                binding.localVideoViewContainer.addView(localScreenShareStreamView)
            }else{
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading local camera")
                removeViewFromParent(localStreamView!!)
                binding.localVideoViewContainer.addView(localStreamView)
            }
        }
    }


    private fun updateControlUI(){
        if (viewModel.currentCallLiveData?.haveOngoingCall() == true) {
            if(viewModel.isVideoCall()){
                binding.videoActionButtonContainer.visibility = View.VISIBLE
                binding.actionButtonContainer.visibility = View.GONE
            }else {
                binding.actionButtonContainer.visibility = View.VISIBLE
                binding.videoActionButtonContainer.visibility = View.GONE
            }

            val activeParticipant = viewModel.currentParticipants.filter {
                val participantState = viewModel.currentCallLiveData!!.getStateFor(it.id)
                (participantState == CallState.ONGOING || participantState == CallState.RINGING || participantState == CallState.READY)
            }

            binding.addContactButton.visibility = if(activeParticipant.size >= 7) View.INVISIBLE else View.VISIBLE
            binding.videoCallButton.visibility = if(activeParticipant.size > 2) View.GONE else View.VISIBLE
            binding.videoCallButtonText.visibility = if(activeParticipant.size > 2) View.GONE else View.VISIBLE

        }else{
            binding.actionButtonContainer.visibility = View.GONE
            binding.videoActionButtonContainer.visibility = View.GONE
            binding.addContactButton.visibility = View.INVISIBLE
        }
    }
    override fun onBackPressed() {
        //disable
    }

    fun onTap(v: View?) {
        if (viewModel.isVideoCall()){
            if (viewOnTap) {
                viewOnTap = false
                onHiddenControl()
                handler.removeCallbacksAndMessages(null)
            } else {
                viewOnTap = true
                onShowControl()
                handler.postDelayed({
                    viewOnTap = false
                    onHiddenControl()
                    handler.removeCallbacksAndMessages(null)
                }, 3000)
            }
        }
    }

    fun onHiddenControl() {
        binding.declineButton
                .animate()
                .translationY(binding.declineButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.declineButton.visibility = View.INVISIBLE
                    }
                })

        binding.switchCameraButton
                .animate()
                .translationY(binding.switchCameraButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.switchCameraButton.visibility = View.INVISIBLE
                    }
                })

        binding.muteCamVideoButton
                .animate()
                .translationY(binding.muteCamVideoButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteCamVideoButton.visibility = View.INVISIBLE
                    }
                })

        binding.muteMicVideoButton
                .animate()
                .translationY(binding.muteMicVideoButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteMicVideoButton.visibility = View.INVISIBLE
                    }
                })

        binding.shareScreenButton
                .animate()
                .translationY(binding.shareScreenButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.shareScreenButton.visibility = View.INVISIBLE
                    }
                })
    }

    fun onShowControl() {
        binding.declineButton.visibility =  View.VISIBLE
        binding.declineButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.declineButton.visibility = View.VISIBLE
                    }
                })

        binding.switchCameraButton.visibility =  View.VISIBLE
        binding.switchCameraButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.switchCameraButton.visibility = View.VISIBLE
                    }
                })

        binding.muteCamVideoButton.visibility =  View.VISIBLE
        binding.muteCamVideoButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteCamVideoButton.visibility = View.VISIBLE
                    }
                })

        binding.muteMicVideoButton.visibility =  View.VISIBLE
        binding.muteMicVideoButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteMicVideoButton.visibility = View.VISIBLE
                    }
                })

        binding.shareScreenButton.visibility =  View.VISIBLE
        binding.shareScreenButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.shareScreenButton.visibility = View.VISIBLE
                    }
                })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == REQUEST_ADD_CALL_PARTICIPANT && resultCode == RESULT_OK && data != null){
            val addedID = data.getIntExtra(AddCallActivity.RESULT_ADD_CALL_PARTICIPANT_ID, 0)
            val addedName = data.getStringExtra(AddCallActivity.RESULT_ADD_CALL_PARTICIPANT_NAME)
            val addedAvatar = data.getStringExtra(AddCallActivity.RESULT_ADD_CALL_PARTICIPANT_AVATAR)

            if(addedID != 0 && addedName != null && addedAvatar != null){
                val newParticipant =
                    CallParticipantModel(id = addedID, name = addedName, avatar = addedAvatar)
                viewModel.inviteParticipant(newParticipant)
                callTimer.start()
            }

        }
    }

    companion object {
        private const val PERMISSION_REQ_ID_CALL = 22
        private const val REQUEST_ADD_CALL_PARTICIPANT = 143

    }

    override fun onSlideComplete(view: SlideToActView) {
        view.text = ""
        //mainHandler.looper.quitSafely()
        val myState = viewModel.getMyState()
        if(myState == CallState.RINGING) {
            applicationContext.stopService(Intent(this, IncomingAudioCallService::class.java))
            stopRingtoneAudio()
            stopRingtoneVibrate()
            viewModel.sendAcceptSignal()
        }

    }
}