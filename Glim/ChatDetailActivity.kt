package chat.glim.mobile.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Parcelable
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import chat.glim.mobile.R
import chat.glim.mobile.adapter.ChatDetailAdapter
import chat.glim.mobile.adapter.ChatDetailAdapterListener
import chat.glim.mobile.adapter.MentionAdapter
import chat.glim.mobile.custom.RecyclerViewEndlessScrollListener
import chat.glim.mobile.custom.ReplyItemTouchHelper
import chat.glim.mobile.data.model.*
import chat.glim.mobile.data.model.entities.*
import chat.glim.mobile.databinding.ActivityChatDetailBinding
import chat.glim.mobile.services.XMPPService
import chat.glim.mobile.utils.*
import chat.glim.mobile.utils.Constant.CHAT_ONLINE_STATUS
import chat.glim.mobile.utils.Constant.CHAT_STANZA_ID
import chat.glim.mobile.utils.Constant.CHAT_TARGET_ID
import chat.glim.mobile.utils.Constant.CHAT_TARGET_TYPE
import chat.glim.mobile.utils.Constant.CHAT_UNKNOW_CONTACT
import chat.glim.mobile.utils.Constant.CONTACT_REGISTERED_PARCELABLE
import chat.glim.mobile.utils.Constant.DAY
import chat.glim.mobile.utils.Constant.GROUP_ID
import chat.glim.mobile.utils.Constant.USER_ID
import chat.glim.mobile.utils.Constant.keyMention
import chat.glim.mobile.viewmodel.*
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.fxn.pix.Options
import com.fxn.pix.Pix
import com.google.android.exoplayer2.util.MimeTypes
import com.jakewharton.rxbinding.widget.RxTextView
import com.vanniktech.emoji.EmojiPopup
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.math.abs

class ChatDetailActivity : BaseChangePhotoActivity(), OnKeyboardVisibilityListener,
    ChatDetailAdapterListener {

    companion object {
        private const val REQUEST_CODE_TAKE_CAMERA = 100
        private const val REQUEST_CODE_CONTACT = 101
        private const val REQUEST_CODE_LOCATION = 102
        private const val REQUEST_CODE_OPEN_GALLERY = 105
        private const val REQUEST_CODE_OPEN_GALLERY_WALLPAPER = 115
        private const val REQUEST_CODE_OPEN_DOCUMENT = 106
        private const val REQUEST_CODE_OPEN_AUDIO = 107
        private const val REQUEST_CODE_WRITE_STORAGE = 201
        private const val REQUEST_CODE_PREVIEW = 202
        private const val REQUEST_CODE_FORWARD = 203
        private const val REQUEST_CODE_ADD_CONTACT = 204
        private const val REQUEST_CODE_RECORD_AUDIO = 205
    }

    private var mentionList: ArrayList<ContactRegistered> = arrayListOf<ContactRegistered>()

    private var accountSetting: AccountSettings? = null
    private lateinit var emotePopup: EmojiPopup
    private lateinit var binding: ActivityChatDetailBinding
    private lateinit var adapter: ChatDetailAdapter
    private lateinit var mentionAdapter: MentionAdapter
    private lateinit var scrollListener: RecyclerViewEndlessScrollListener
    private val viewModel: ChatDetailViewModel by viewModel()
    private var returnValue = ArrayList<String>()
    private var listChat = mutableListOf<ChatDetail>()
    private var state: Parcelable? = null
    private var targetId = 0
    private var chatType = 0
    private var isPreviousSearch = false
    private lateinit var setupPickImage: Options
    private lateinit var user: User
    private lateinit var lifecycleObserver: LifecycleEventObserver
    private var repliedChat: ChatDetail? = null
    private var onlineStatusText = ""
    private var isTyping = false
    private var isOtherTyping = false
    private var otherypingUsername = ""
    private var isBlockedContact = false
    private var toneMediaPlayer = MediaPlayer()
    private var targetStanzaId = ""
    private var tempMessage = ""

    private var output: String? = null
    private var mediaRecorder: MediaRecorder? = null
    private var stateRecord: Boolean = false

    private var x1 = 0f
    private var x2 = 0f
    private var second = -1
    private var minute = 0
    private var index = 0
    private var isLeftGroup = 0

    var countDownTimer: CountDownTimer? = null
    var isDoneForward = false
    var currentGroup: Group? = null


    private var unknowContact: Contact? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initLifecycleObserver()

        binding = ActivityChatDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetId = intent.getIntExtra(CHAT_TARGET_ID, 0)
        chatType = intent.getIntExtra(CHAT_TARGET_TYPE, 0)
        if (intent?.hasExtra(CHAT_STANZA_ID) == true)
            targetStanzaId = intent.getStringExtra(CHAT_STANZA_ID) ?: ""

        if (intent?.hasExtra(CHAT_UNKNOW_CONTACT)!!) unknowContact =
            intent?.getParcelableExtra(CHAT_UNKNOW_CONTACT)
        if (intent?.hasExtra(CONTACT_REGISTERED_PARCELABLE)!!) unknowContact =
            intent?.getParcelableExtra<ContactRegistered>(CONTACT_REGISTERED_PARCELABLE)
                ?.toContact()

        if (targetId == 0) {
            finish()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val visibleNotification =
                notificationManager.activeNotifications.filter { it.tag == targetId.toString() }
            visibleNotification.forEach {
                notificationManager.cancel(it.tag, it.id)
            }
        }

        setupPickImage = Options.init()
            .setRequestCode(REQUEST_CODE_TAKE_CAMERA)
            .setCount(5)
            .setFrontfacing(false)
            .setPreSelectedUrls(returnValue)
            .setMode(Options.Mode.All)
            .setSpanCount(4)
            .setPath(Constant.GLIM_IMAGE_DIR)
            .setVideoDurationLimitinSeconds(29)
            .setScreenOrientation(Options.SCREEN_ORIENTATION_PORTRAIT)

        initView()
        initObserver()
    }

    override fun onDestroy() {
        super.onDestroy()

        viewModel.onClear()
        MediaPlayerUtils.releaseMediaPlayer()
        stopChatTone()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
    }

    override fun onPause() {
        super.onPause()
        state = binding.chatRecyclerView.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        if (unknowContact == null)
            viewModel.initialize(targetId, ChatType.fromInt(chatType))
        else
            viewModel.initUnknowContact(unknowContact!!)
        if (state != null) {
            binding.chatRecyclerView.layoutManager?.onRestoreInstanceState(state)
        }
    }

    private fun initLifecycleObserver() {
        lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.resetXmppJid()
                Lifecycle.Event.ON_RESUME -> viewModel.setXmppJidToLatestOne()
                else -> {
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        //setup chat list
        emotePopup = EmojiPopup.Builder.fromRootView(binding.root).build(binding.messageEditText)

        adapter = ChatDetailAdapter(this, this)

        binding.listMention.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.VERTICAL, false
        )
        mentionAdapter = MentionAdapter(this, mentionList)

        binding.chatRecyclerView.adapter = adapter
        val layoutManager = LinearLayoutManager(applicationContext)
        layoutManager.reverseLayout = true
        layoutManager.stackFromEnd = true
        binding.chatRecyclerView.layoutManager = layoutManager
        scrollListener = object : RecyclerViewEndlessScrollListener(layoutManager) {

            override fun onLoadMore(page: Int, totalItemsCount: Int, view: RecyclerView?) {
                if (isSearchViewVisible()){
                    viewModel.getChatDetail(page*1000)
                } else{
                    viewModel.getChatDetail(page)
                }
            }

            override fun onReachEnd() {
                if (isPreviousSearch && adapter.isNoSearchItemHighlighted()) {
                    onSearchNotFound()
                }
            }
        }
        binding.chatRecyclerView.addOnScrollListener(scrollListener)
        val replyItemTouchHelper = ReplyItemTouchHelper(this,
            object : ReplyItemTouchHelper.ItemTouchHelperListener {
                override fun showReplyView(chat: ChatDetail) {
                    onReplyChat(chat)
                }
            }
        )
        ItemTouchHelper(replyItemTouchHelper).attachToRecyclerView(binding.chatRecyclerView)

        //attachment button
        binding.attachButton.setOnClickListener {
            if (!isBlockedContact) {
                if (binding.attachmentLayout.visibility == View.GONE) showAttachmentView()
                else hideAttachmentView()
            } else {
                blockedDialog(getString(R.string.open_blocking_contact_message))
            }
        }
        binding.attachOutsideLayout.setOnClickListener { hideAttachmentView() }

        binding.contactButton.setOnClickListener {
            startActivityForResult(
                Intent(this, SelectContactForAttachActivity::class.java),
                REQUEST_CODE_CONTACT
            )
        }

        binding.locationButton.setOnClickListener {
            if (isNetworkConnected()){
                startActivityForResult(
                        Intent(this, PickLocationActivity::class.java),
                        REQUEST_CODE_LOCATION
                )
            } else {
                showNoInternetDialog(getString(R.string.no_internet))
            }
        }

        binding.cameraButton.setOnClickListener {
            Pix.start(this, setupPickImage)
        }

        binding.galleryButton.setOnClickListener {
            checkGalleryPermission()
        }

        binding.documentButton.setOnClickListener {
            openDocument()
        }

        binding.audioButton.setOnClickListener {
            openAudio()
        }

        //send/record button
        setKeyboardVisibilityListener(this)
        binding.rightButton.tag = 1
        binding.rightButton.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    x1 = motionEvent.x
                    if (!isBlockedContact) {
                        if (view.tag == 1) {
                            //send audio record
                            checkVoiceRecordingPermission()
                        } else {
                            //send message
                            val chatContent = binding.messageEditText.text.toString()
                            if (chatContent.isNotBlank()) {
                                doAsync {
                                    val content = mentionParsing(chatContent)
                                    viewModel.insertChatDetail(
                                        ChatDetail(message = content),
                                        repliedChat,
                                        this@ChatDetailActivity,
                                        null,
                                        currentGroup
                                    )
                                    repliedChat = null
                                    uiThread {
                                        viewModel.playChatTone()
                                        binding.replyCancelButton.callOnClick()
                                        binding.messageEditText.setText("")
                                    }
                                }
                            }
                        }
                    } else {
                        blockedDialog(getString(R.string.open_blocking_contact_message))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    x2 = motionEvent.x
                    val delta = x2 - x1
                    if (view.tag == 1 && stateRecord) {
                        binding.messagingLayout.visibility = View.VISIBLE
                        binding.recordingLayout.visibility = View.GONE
                        stopRecording()
                        //swipe left to cancel record
                        if (abs(delta) > 100) {
                            if (delta < 0) {
                                if (File(output).exists()) File(output).delete()
                            }
                        } else {
                            if (second > 0) {
                                viewModel.insertChatDetailWithAttach(
                                    ChatDetail(
                                        mediaType = MediaType.AUDIO,
                                        message = "Audio",
                                        mediaContentLocal = File(output).absolutePath,
                                        originalDocumentName = File(output).name
                                    ), repliedChat, this, null, currentGroup
                                )
                            } else {
                                if (File(output).exists()) File(output).delete()
                            }
                        }
                        stopTimer()
                    }
                }
            }
            true

        }

        RxTextView.textChanges(binding.messageEditText)
            .debounce(3, TimeUnit.SECONDS)
            .subscribe({
                Log.d("TYPING-PERSONAL", "bounced $isTyping")
                isTyping = false
                setTypingStatus(false)
            },{

            })

        binding.messageEditText.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View?, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    val cursorPos = binding.messageEditText.selectionStart
                    val content = binding.messageEditText.text.toString()

                    //move cursor to end of mention (if cursor within mention)
                    if (binding.messageEditText.text.toString().contains("@")) {
                        var isMoved = false
                        viewModel.contactLocal?.forEach { contact ->
                            var pos = content.indexOf("@" + contact.name) + 1

                            // count number mentioned 'name'
                            var i = 0
                            val p: Pattern = Pattern.compile("@" + contact.name)
                            val m: Matcher = p.matcher(content)
                            while (m.find()) {
                                i++
                            }
                            //loop mention
                            for (n in 0 until i) {
                                if (!isMoved) {
                                    if (cursorPos >= pos && cursorPos <= (pos + contact.name.length)) {
                                        binding.messageEditText.setSelection(pos + contact.name.length)
                                        isMoved = true
                                    }
                                }

                                pos = content.indexOf(
                                    "@" + contact.name,
                                    pos + contact.name.length
                                ) + 1
                            }
                        }
                        if (!isMoved) {
                            viewModel.currentGroup.value?.members?.forEach { user ->
                                if (content.contains("@" + user.phone)) {
                                    var pos = content.indexOf("@" + user.phone) + 1

                                    // count number mentioned 'name'
                                    var i = 0
                                    val p: Pattern = Pattern.compile("@" + user.phone)
                                    val m: Matcher = p.matcher(content)
                                    while (m.find()) {
                                        i++
                                    }
                                    //loop mention
                                    for (n in 0 until i) {
                                        if (!isMoved) {
                                            if (cursorPos >= pos && cursorPos <= (pos + user.phone.length)) {
                                                binding.messageEditText.setSelection(pos + user.phone.length)
                                                isMoved = true
                                            }
                                        }

                                        pos = content.indexOf(
                                            "@" + user.phone,
                                            pos + user.phone.length
                                        ) + 1
                                    }
                                }
                            }
                        }
                    }


                }
            }
        })

        binding.messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                if (!isTyping) {
                    setTypingStatus(true)
                    isTyping = true
                }
                if (binding.messageEditText.length() > 0) {
                    if (!isSearchViewVisible()) {
                        binding.chatRecyclerView.scrollToPosition(0)
                    }
                    binding.rightButton.setImageResource(R.drawable.ic_send_chat)
                    binding.rightButton.tag = 2
                } else {
                    binding.rightButton.setImageResource(R.drawable.ic_microphone)
                    binding.rightButton.tag = 1
                }

                if (binding.messageEditText.text.toString().contains("@")) {

                    if (binding.messageEditText.text.toString()
                            .lastIndexOf("@") == binding.messageEditText.length() - 1
                    ) {
                        if (binding.messageEditText.selectionStart == binding.messageEditText.length()) {
                            //populate mention member
                            getMentionMember("")
                        }

                    } else {
                        var isRemovedMention = false
                        val cursorPos = binding.messageEditText.selectionStart
                        if (before > count) {

                            Log.e("FULL TEXT", ":" + binding.messageEditText.text.toString())
                            var removedText = ""
                            viewModel.contactLocal?.forEach { contact ->
                                //check any deleted mention
                                val mentionName = contact.name.substring(0, contact.name.length - 1)
                                if (binding.messageEditText.text.toString()
                                        .contains("@" + mentionName)
                                ) {
                                    if (cursorPos >= ("@" + mentionName).length) {
                                        if (binding.messageEditText.text.toString().substring(
                                                cursorPos - ("@" + mentionName).length,
                                                cursorPos
                                            ).equals("@" + mentionName)
                                        ) {
                                            isRemovedMention = true
                                            removedText = "@" + mentionName
                                        }
                                    }

                                }
                            }

                            if (!isRemovedMention) {
                                viewModel.currentGroup.value?.members?.forEach { user ->
                                    val mentionName = user.phone.substring(0, user.phone.length - 1)
                                    if (binding.messageEditText.text.toString()
                                            .contains("@" + mentionName)
                                    ) {

                                        if (cursorPos >= ("@" + mentionName).length) {
                                            if (binding.messageEditText.text.toString().substring(
                                                    cursorPos - ("@" + mentionName).length,
                                                    cursorPos
                                                ).equals("@" + mentionName)
                                            ) {
                                                isRemovedMention = true
                                                removedText = "@" + mentionName
                                            }
                                        }

                                    }
                                }
                            }

                            if (isRemovedMention) {
                                //check cursor position
                                val textEditable = binding.messageEditText.text

                                val isLast = cursorPos == binding.messageEditText.length()

                                textEditable?.delete(cursorPos - removedText.length, cursorPos)
                                binding.messageEditText.text = textEditable

                                if (isLast)
                                    binding.messageEditText.setSelection(binding.messageEditText.length())

                                binding.listMention.gone()
                                mentionList.clear()
                            }

                        }

                        if (!isRemovedMention) {

                            if (binding.messageEditText.selectionStart == binding.messageEditText.length()) {
                                val lastpos =
                                    binding.messageEditText.text.toString().lastIndexOf("@")
                                val text =
                                    binding.messageEditText.text.toString().substring(lastpos + 1)

                                getMentionMember(text) //populate mention member
                            } else {
                                val curText =
                                    binding.messageEditText.text.toString().substring(0, cursorPos)

                                if (curText.contains("@")) {
                                    val lastpos = curText.lastIndexOf("@")
                                    val text = curText.substring(lastpos + 1)
                                    getMentionMember(text)
                                }
                            }

                        }

                    }
                } else {
                    mentionList.clear()
                    binding.listMention.gone()
                }

                if (-1 != s.toString().indexOf("\n")) {
                    if (accountSetting?.chatEnterToSend == 2) {
                        val message = binding.messageEditText.text.toString()
                            .substring(0, binding.messageEditText.text.toString().length - 1)

                        if (message.isNotBlank()) {
                            viewModel.insertChatDetail(
                                ChatDetail(message = message),
                                repliedChat,
                                baseContext,
                                null,
                                    currentGroup
                            )
                            repliedChat = null
                        }
                        binding.replyCancelButton.callOnClick()
                        binding.messageEditText.setText("")
                    } else if (accountSetting?.chatEnterToSend == 1) {
                        // ...
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {


            }
        })



        binding.slideTextView.setOnClickListener {
            binding.recordingLayout.visibility = View.GONE
            binding.messagingLayout.visibility = View.VISIBLE
        }

        binding.infoLayout.setOnClickListener {
            if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                val intent = Intent(this, FriendProfileActivity::class.java)
                intent.putExtra(CHAT_TARGET_ID, targetId)
                intent.putExtra(CHAT_TARGET_TYPE, chatType)
                intent.putExtra(CHAT_ONLINE_STATUS, onlineStatusText)

                startActivity(intent)
            } else {
                val intent = Intent(this, GroupInfoActivity::class.java)
                intent.putExtra(GROUP_ID, targetId)
                startActivity(intent)
            }
        }

        binding.photoImageView.setOnClickListener {
            if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                val intent = Intent(this, FriendProfileActivity::class.java)
                intent.putExtra(CHAT_TARGET_ID, targetId)
                intent.putExtra(CHAT_TARGET_TYPE, chatType)
                intent.putExtra(CHAT_ONLINE_STATUS, onlineStatusText)

                startActivity(intent)
            } else {
                val intent = Intent(this, GroupInfoActivity::class.java)
                intent.putExtra(GROUP_ID, targetId)
                startActivity(intent)
            }
        }

        binding.emojiButton.setOnClickListener {
            binding.messageEditText.focusAndShowKeyboard()
            if (emotePopup.isShowing) {
                binding.emojiButton.setImageResource(R.drawable.ic_emoji)
                emotePopup.dismiss()
            } else {
                binding.emojiButton.setImageResource(R.drawable.ic_keyboard_grey600_24dp)
                emotePopup.show()
            }
        }

        binding.replyCancelButton.setOnClickListener {
            repliedChat = null
            binding.layoutReply.gone()
        }

        binding.searchBackButton.setOnClickListener {
            onBackPressed()
        }

        binding.searchEditText.setOnEditorActionListener { editText, actionId, _ ->
            val query = editText.text.toString()
            if (actionId == EditorInfo.IME_ACTION_SEARCH && query.isNotBlank()) {
                searchPrevious(query)
            }
            true
        }

        binding.prevButton.setOnClickListener {
            val query = binding.searchEditText.text.toString()
            if (query.isNotBlank()) {
                searchPrevious(query)
            }
        }
        binding.nextButton.setOnClickListener {
            val query = binding.searchEditText.text.toString()
            if (query.isNotBlank()) {
                searchNext(query)
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun getMentionMember(name: String) {

        mentionList.clear()
        var mentionMembers = arrayListOf<ContactRegistered>()
        val contactData = arrayListOf<ContactRegistered>()

        viewModel.contactLocal?.forEach {
            if (it.name.toLowerCase().contains(name.toLowerCase())) {
                contactData.add(it)
            }
        }

        viewModel.currentGroup.value?.members?.forEach { userLocal ->
            if (user.id != userLocal.id) {
                var added = false

                contactData.forEach {
                    if (userLocal.id == it.id) {
                        mentionMembers.add(it)
                        added = true
                    }
                }
                if (!added) {
                    if (userLocal.phone.contains(name)) {
                        var isExist = false
                        viewModel.contactLocal?.forEach {
                            if (it.phone == userLocal.phone) {
                                isExist = true
                            }
                        }
                        if (!isExist)
                            mentionMembers.add(userLocal.toContactRegistered())
                    }
                }
            }
        }

        mentionList = mentionMembers
        mentionAdapter = MentionAdapter(this, mentionList)
        binding.listMention.adapter = mentionAdapter
        mentionAdapter.setOnClickListener(object : MentionAdapter.OnClickListener {
            override fun onClick(contact: ContactRegistered) {
                binding.listMention.gone()
                val cursorPos = binding.messageEditText.selectionStart

                // set prefix, (mention), and suffix
                var prefix = binding.messageEditText.text.toString().substring(0, cursorPos)
                val suffix = binding.messageEditText.text.toString()
                    .substring(cursorPos, binding.messageEditText.length())

                var value = ""
                if (prefix.lastIndexOf("@") == prefix.length) {
                    value = prefix + contact.name + " " + suffix
                } else {
                    val lastPos = prefix.lastIndexOf("@")
                    prefix = prefix.substring(0, lastPos + 1)
                    value = prefix + contact.name + " " + suffix
                }

                val textMsg = mentionStyling(value)
                binding.messageEditText.setText(textMsg)

                binding.messageEditText.setSelection(binding.messageEditText.length())
            }
        })

        mentionAdapter.notifyDataSetChanged()
        if (mentionList.size > 0)
            binding.listMention.visible()
        else
            binding.listMention.gone()

    }

    private fun mentionStyling(value: String): SpannableStringBuilder {
        val content = SpannableStringBuilder(value)

        viewModel.contactLocal?.forEach { contact ->
            if (user.id != contact.id) {
                if (value.contains("@" + contact.name)) {

                    var pos = content.indexOf("@" + contact.name) + 1

                    // count number mentioned 'name'
                    var i = 0
                    val p: Pattern = Pattern.compile("@" + contact.name)
                    val m: Matcher = p.matcher(content)
                    while (m.find()) {
                        i++
                    }
                    //loop mention
                    for (n in 0 until i) {

                        content.setSpan(
                            ForegroundColorSpan(Color.BLUE),
                            pos,
                            pos + contact.name.length,
                            Spannable.SPAN_EXCLUSIVE_INCLUSIVE
                        )
                        pos = content.indexOf("@" + contact.name, pos + contact.name.length) + 1
                    }
                }
            }
        }

        viewModel.currentGroup.value?.members?.forEach { userLocal ->
            if (user.id != userLocal.id) {
                if (value.contains("@" + userLocal.phone)) {
                    var pos = content.indexOf("@" + userLocal.phone) + 1

                    // count number mentioned 'name'
                    var i = 0
                    val p: Pattern = Pattern.compile("@" + userLocal.phone)
                    val m: Matcher = p.matcher(content)
                    while (m.find()) {
                        i++
                    }
                    //loop mention
                    for (n in 0 until i) {
                        content.setSpan(
                            ForegroundColorSpan(Color.BLUE),
                            pos,
                            pos + userLocal.phone.length,
                            Spannable.SPAN_EXCLUSIVE_INCLUSIVE
                        )
                        pos =
                            content.indexOf("@" + userLocal.phone, pos + userLocal.phone.length) + 1
                    }
                }
            }
        }

        return content
    }

    private fun mentionParsing(text: String): String {
        var parsedText = text

        viewModel.contactLocal?.sortedByDescending {
            it.name.length
        }
        viewModel.contactLocal?.forEach {
            if (text.contains("@" + it.name)) {
                // possible (close) namesake
                var isNameSake = false
                val nameSakeList = arrayListOf<String>()
                val nameSakeLength = arrayListOf<Int>()
                viewModel.contactLocal?.forEach { contact ->
                    if (contact.name.contains(it.name) && contact.name != it.name) {
                        nameSakeList.add(contact.name)
                        nameSakeLength.add(contact.name.length)
                        isNameSake = true
                    }
                }
                if (isNameSake) {

                    //get position name sake

                    var pos = parsedText.indexOf("@" + it.name) + 1

                    // count number mentioned 'name'
                    var i = 0
                    val p: Pattern = Pattern.compile("@" + it.name)
                    val m: Matcher = p.matcher(parsedText)
                    while (m.find()) {
                        i++
                    }
                    for (n in 0 until i) {
                        var checkedNameSake = false

                        val name = parsedText.substring(
                            pos,
                            pos + it.name.length
                        )
                        nameSakeList.forEach {
                            if (pos + it.length <= parsedText.length) {
                                val nameSake = parsedText.substring(pos,pos + it.length)
                                if (it == nameSake)
                                    checkedNameSake = true
                            } else {
                                val nameSake = parsedText.substring(pos,parsedText.length)
                                if (it == nameSake)
                                    checkedNameSake = true
                            }

                        }

                        if (!checkedNameSake) {
                            //
                            val textEditable = SpannableStringBuilder(parsedText)
                            textEditable.delete(pos, pos + it.name.length)
                            textEditable.insert(pos, keyMention + it.id)
                            parsedText = textEditable.toString()
//                            parsedText = parsedText.replace("@" + it.name, "@" + keyMention + it.id)
                        }
                        pos = parsedText.indexOf("@" + name, pos + name.length) + 1
                    }
                } else {
                    var data = it
                    viewModel.currentGroup.value?.members?.forEach {
                        if (data.phone.contains(it.phone)) {
                            parsedText = parsedText.replace("@" + it.name, "@" + keyMention + it.id)
                        }
                    }
                }
            }
        }
        viewModel.currentGroup.value?.members?.forEach {
            if (text.contains("@" + it.phone)) {

                // possible (close) namesake
                var isNameSake = false
                val nameSakeList = arrayListOf<String>()
                val nameSakeLength = arrayListOf<Int>()
                viewModel.currentGroup.value?.members?.forEach { user ->
                    if (user.phone.contains(it.phone) && user.phone != it.phone) {
                        nameSakeList.add(user.phone)
                        nameSakeLength.add(user.phone.length)
                        isNameSake = true
                    }
                }
                if (isNameSake) {

                    //get position name sake

                    var pos = parsedText.indexOf("@" + it.phone) + 1

                    // count number mentioned 'name'
                    var i = 0
                    val p: Pattern = Pattern.compile("@" + it.phone)
                    val m: Matcher = p.matcher(parsedText)
                    while (m.find()) {
                        i++
                    }
                    for (n in 0 until i) {
                        var checkedNameSake = false

                        val name = parsedText.substring(
                            pos,
                            pos + it.phone.length
                        )
                        nameSakeList.forEach {
                            if (pos + it.length <= parsedText.length) {
                                val nameSake = parsedText.substring(pos,pos + it.length)
                                if (it == nameSake)
                                    checkedNameSake = true
                            } else {
                                val nameSake = parsedText.substring(pos,parsedText.length)
                                if (it == nameSake)
                                    checkedNameSake = true
                            }

                        }

                        if (!checkedNameSake) {
                            //
                            val textEditable = SpannableStringBuilder(parsedText)
                            textEditable.delete(pos, pos + it.phone.length)
                            textEditable.insert(pos, keyMention + it.id)
                            parsedText = textEditable.toString()
                        }
                        pos = parsedText.indexOf("@" + name, pos + name.length) + 1
                    }
                } else {
                    parsedText = parsedText.replace("@" + it.phone, "@" + keyMention + it.id)
                }

//                parsedText = parsedText.replace("@" + it.phone, "@" + keyMention + it.id)
            }
        }
        return if (parsedText == "") text else parsedText
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            Log.e("active", cm.activeNetworkInfo.toString())
            Log.e("connect", cm.activeNetworkInfo.isConnected.toString())
            cm.activeNetworkInfo != null && cm.activeNetworkInfo.isConnected
        } catch (e: java.lang.Exception) {
            // TODO Auto-generated catch block
            false
        }
    }

    private fun prepareRecording() {
        mediaRecorder = MediaRecorder()
        output = createFilenameAudioRecord(this)

        mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder?.setOutputFile(output)
    }

    private fun startRecording() {
        binding.messagingLayout.visibility = View.GONE
        binding.recordingLayout.visibility = View.VISIBLE

        try {
            prepareRecording()
            mediaRecorder?.prepare()
            mediaRecorder?.start()
            stateRecord = true
            showTimer()
        } catch (e: Exception) {
            e.printStackTrace()
            binding.messagingLayout.visibility = View.VISIBLE
            binding.recordingLayout.visibility = View.GONE
        }
    }

    private fun stopRecording() {
        if (stateRecord) {
            if (second >= 0) {
                try {
                    mediaRecorder?.stop()
                    mediaRecorder?.release()
                    stateRecord = false
                } catch (e: Exception) {
                    mediaRecorder?.release()
                    stateRecord = false
                }


            }
        }
    }


    //display recording time
    private fun showTimer() {
        countDownTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(p0: Long) {
                second++
                binding.timerTextView.text = recorderTime()
            }

            override fun onFinish() {}
        }.start()
    }

    private fun stopTimer() {
        countDownTimer?.cancel()
        second = -1
        minute = 0
    }

    //recorder time
    @SuppressLint("DefaultLocale")
    private fun recorderTime(): String {
        if (second == 60) {
            minute++
            second = 0
        }
        return String.format("%02d:%02d", minute, second)
    }

    private fun checkGalleryPermission(isChangeWallpaper: Boolean = false) {
        val checkSelfPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (checkSelfPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                if (isChangeWallpaper) REQUEST_CODE_OPEN_GALLERY_WALLPAPER
                else REQUEST_CODE_OPEN_GALLERY
            )
        } else {
            openGallery(isChangeWallpaper)
        }
    }

    private fun checkWriteStoragePermission(chat: ChatDetail) {
        val checkSelfPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (checkSelfPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_WRITE_STORAGE
            )
        } else {
            downloadAttach(chat)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_OPEN_GALLERY || requestCode == REQUEST_CODE_OPEN_GALLERY_WALLPAPER) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                openGallery(requestCode == REQUEST_CODE_OPEN_GALLERY_WALLPAPER)
            else
                Toast.makeText(this, "You denied the permission", Toast.LENGTH_SHORT).show()
        } else if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            if (grantResults.size == 3 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                grantResults[2] == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                showDeniedAudioPermissionDialog()
            }
        }
    }

    private fun openGallery(isChangeWallpaper: Boolean = false) {
        val gallery =
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*,video/*"
            }
        startActivityForResult(
            gallery,
            if (isChangeWallpaper) REQUEST_CODE_OPEN_GALLERY_WALLPAPER
            else REQUEST_CODE_OPEN_GALLERY
        )
    }

    private fun openDocument() {
        val document =
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(document, REQUEST_CODE_OPEN_DOCUMENT)
    }

    private fun openAudio() {
        startActivityForResult(
            Intent(this, AudioAttachActivity::class.java),
            REQUEST_CODE_OPEN_AUDIO
        )
    }

    private fun initObserver() {
        viewModel.loading.observe(this, Observer {
            it?.let {
                if (it) showLoading() else hideLoading()
            }
        })

        viewModel.getContactLocal()

        viewModel.currentContactAvailability.observe(this, Observer {
            it?.let {
                Log.d(
                    "TYPING-PERSONAL",
                    "got notif availability " + (viewModel.currentContact != null)
                )
                if (viewModel.currentContact != null) {
                    setUserStatusText(
                        viewModel.currentContact?.isAvailable ?: false,
                        isOtherTyping,
                        otherypingUsername
                    )
                }
            }
        })

        viewModel.state.observe(this, Observer {
            when (it) {
                is ChatTargetReady -> {
                    //load chat wallpaper
                    loadWallpaper()

                    //load avatar
                    Glide.with(applicationContext)
                        .load(it.avatar)
                        .signature(ObjectKey(signatureDate(DAY)))
                        .placeholder(R.color.greyPhotoBorder)
                        .error(R.color.greyPhotoBorder)
                        .into(binding.photoImageView)

                    //load name
                    binding.nameTextView.text = it.name

                    //get all chats
                    scrollListener.resetState()
                    viewModel.getChatDetail()
                    invalidateOptionsMenu()

                    viewModel.currentChatLiveData?.observe(this, Observer {
                        if (it.isNotEmpty()) {
                            Log.d(
                                "TYPING-PERSONAL",
                                "got notif currentChatLiveData " + (it.first().isTyping)
                            )
                            isOtherTyping = it.first().isTyping
                            otherypingUsername = it.first().typingUserName
                            setUserStatusText(
                                viewModel.currentContact?.isAvailable ?: false,
                                isOtherTyping,
                                otherypingUsername
                            )
                        }

                    })
                    //check for forwarded mesages
                    if (!isDoneForward) {
                        isDoneForward = true
                        intent.getStringArrayExtra(Constant.CHAT_FORWARDED_STANZAS)?.let {
                            viewModel.sendForwardedMessages(it)
                        }
                        intent.getStringExtra(Constant.CONTENT_TYPE)?.let { type ->
                            if (type != null) {
                                val content = intent.getStringExtra(Constant.CONTENT)
                                viewModel.sendForwardedStatus(content, type)
                            }

                        }
                    }

                }
                is AttachFailed -> {
                    val holder =
                        binding.chatRecyclerView.getChildAt(it.position - 1) as ChatDetailAdapter.VideoViewHolder
                    holder.binding.ivUpload.visibility

                }
                is ChatTargetInvalid -> {
                    finish()
                }
                is MuteActionSuccess -> invalidateOptionsMenu()
                is SettingsActionFailed -> {
                    val message = it.message ?: getString(R.string.error_timeoutConnection)
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                }
                is ChatUserReady -> {
                    user = it.user!!
                }
                is AddContactSuccess -> {
                    val intent = Intent(this, ChatDetailActivity::class.java)
                    intent.putExtra(CHAT_TARGET_ID, targetId)
                    intent.putExtra(CHAT_TARGET_TYPE, chatType)
                    startActivity(intent)
                    finish()
                }
                is AddContactFail -> {
                    val message = it.message ?: getString(R.string.error_timeoutConnection)
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                }
                is BlockedContact -> {
                    accountSetting = viewModel.accountSettings
                    isBlockedContact = it.value
                }
                is GetStatusByIdSuccess -> {
                    if (it.status.createdAt?.dateIs24HoursOlder() == true) return@Observer

                    if (it.status.senderId == targetId.toLong()) {
                        startActivity(Intent(this, ShowOtherStatusActivity::class.java).apply {
                            putExtra("data", arrayListOf(it.status))
                            putExtra("type", "RECENT")
                            putExtra("allData", arrayListOf(it.status))
                            putExtra("allDataContent", arrayListOf(it.status))
                        })
                    } else {
                        startActivity(Intent(this, ShowSelfStatusActivity::class.java).apply {
                            putExtra("data", arrayListOf(it.status))
                        })
                    }
                }

                is WallpaperUpdated -> {
                    loadWallpaper()
                }

                is ChatCallParticipantFail -> {
                    showErrorDialog(
                        getString(R.string.alert_title),
                        getString(R.string.call_gather_participant_fail)
                    )
                }

                is ChatCallParticipantReady -> {
                    if (ChatType.fromInt(chatType) == ChatType.GROUP) {
                        // current user participant
                        var curParticipant = CallParticipantModel()
                        ArrayList(it.participants).forEach {
                            if (user.id == it.id)
                                curParticipant = it
                        }

                        if(it.participants.size < 6){
                            val intent = Intent(this, OutgoingCallActivity::class.java)
                            val participant = ArrayList(it.participants)

                            intent.putParcelableArrayListExtra(OutgoingCallActivity.CALL_PARAM_PARTICIPANTS,participant)
                            intent.putExtra(OutgoingCallActivity.CALL_PARAM_TYPE, it.callInvitationType.id)
                            intent.putExtra(OutgoingCallActivity.CALL_PARAM_DIRECTION, CallDirection.OUTGOING.id)
                            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            startActivity(intent)
                        }else {
                            val intent = Intent(this, CallSelectParticipantActivity::class.java)
                            intent.putParcelableArrayListExtra(
                                    OutgoingCallActivity.CALL_PARAM_PARTICIPANTS,
                                    ArrayList(it.participants)
                            )
                            intent.putExtra(
                                    OutgoingCallActivity.CALL_PARAM_TYPE,
                                    it.callInvitationType.id
                            )
                            intent.putExtra(USER_ID, curParticipant)
                            startActivity(intent)
                        }
                    } else {
                        if (!isBlockedContact) {
                            val intent = Intent(this, OutgoingCallActivity::class.java)
                            intent.putParcelableArrayListExtra(
                                    OutgoingCallActivity.CALL_PARAM_PARTICIPANTS,
                                    ArrayList(it.participants)
                            )
                            intent.putExtra(
                                    OutgoingCallActivity.CALL_PARAM_TYPE,
                                    it.callInvitationType.id
                            )
                            intent.putExtra(
                                    OutgoingCallActivity.CALL_PARAM_DIRECTION,
                                    CallDirection.OUTGOING.id
                            )
                            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            startActivity(intent)
                        } else {
                            blockedDialog(getString(R.string.open_blocking_contact_call))
                        }
                    }
                }
                is PlayChatTone -> playChatTone()
                is ChatAccountSettingReady -> {
                    loadWallpaper()
                    adapter.autoTranslate = accountSetting?.autoTranslateOn!!
                    adapter.translateLanguage = accountSetting?.userLanguage!!
                    adapter.notifyDataSetChanged()
                }
                is SuccessDelete -> {
                    viewModel.getChatDetail()
                }
                else -> {
                }
            }
        })

        viewModel.chatDetailData.observe(this, {
            if (listChat.isNotEmpty() && it.isNotEmpty() &&
                it.first().direction == ChatDirection.OTHERS &&
                it.first().createdDate > listChat.first().createdDate
            ) {
                viewModel.playChatTone()
            }
            val chatData = arrayListOf<ChatDetail>()
            //parsing chat msg
            it.forEach { chat ->
                var chatDetail = chat
                chatDetail.srcMessage = chat.message
                chatDetail = parseMsg(chat.message, chatDetail)
                chatData.add(chatDetail)
            }

            for (i in 0 until chatData.size){
                if (chatData[i].message.contains("Anda bergabung dalam grup") && chatData[i].direction.id == -1){
                    chatData.add(chatData.size - 1, chatData[i])
                    chatData.removeAt(i)
                }
            }

            adapter.dataSet = chatData
            listChat = it.toMutableList()
            adapter.notifyDataSetChanged()

            if (scrollListener.isFirstPage()) {

                binding.chatRecyclerView.scrollToPosition(0)

            }

            it.forEach { cd ->
                if (cd.status != MessageStatus.READ && cd.direction == ChatDirection.OTHERS) {
                    viewModel.sendReadReceipt(cd)
                }
            }

            if (targetStanzaId.isNotBlank())
                goToChatWithStanzaId(targetStanzaId)
            else if (isPreviousSearch)
                searchPrevious(binding.searchEditText.text.toString())

            if(it.isNotEmpty()){
                if (it[0].direction == ChatDirection.HEADER){
                    if (it[0].message != tempMessage){
                        tempMessage = it[0].message
                        viewModel.initialize(targetId, ChatType.fromInt(chatType))
                    }
                }
            }
        })

        viewModel.currentGroup.observe(this, { group ->
            currentGroup = group
            viewModel.getChatDetail()
            //set group chat input visibility
            val memberIds = group.members.map { it.id }
            val isMember = memberIds.contains(user.id)
            if (ChatType.fromInt(chatType) == ChatType.GROUP && (group.isLeftGroup == 1 || !isMember)) {
                isLeftGroup = 1
                binding.messagingLayout.visibility = View.GONE
                binding.rightButton.visibility = View.GONE
                binding.recordingLayout.visibility = View.GONE
                binding.blockedMessageText.visibility = View.VISIBLE
            } else {
                isLeftGroup = 0
                binding.messagingLayout.visibility = View.VISIBLE
                binding.rightButton.visibility = View.VISIBLE
                binding.recordingLayout.visibility = View.GONE
                binding.blockedMessageText.visibility = View.GONE
            }
        })
    }

    private fun parseMsg(srcMessage: String, chatDetail: ChatDetail): ChatDetail {

        var msg = srcMessage
        var translatedMsg = chatDetail.messageTranslated
        if (srcMessage.contains("@")) {
            var localValid = false
            var ids = arrayListOf<Int>()
            var length = arrayListOf<Int>()
            var names = arrayListOf<String>()
            var keyword = arrayListOf<String>()
            var status = arrayListOf<String>()
            var unknownContact = arrayListOf<Boolean>()
            viewModel.contactLocal?.forEach { contactData ->
                if (srcMessage.contains("@" + keyMention + contactData.id)) {

                    if (contactData.id == user.id) {
                        msg = msg.replace("@" + keyMention + contactData.id, "@" + "Anda")
                        translatedMsg = translatedMsg.replace("@" + keyMention + contactData.id, "@" + "Anda")
                        length.add(4)
                        names.add("Anda")
                    } else {
                        msg = msg.replace("@" + keyMention + contactData.id, "@" + contactData.name)
                        translatedMsg = translatedMsg.replace("@" + keyMention + contactData.id, "@" + contactData.name)

                        length.add(contactData.name.length)
                        names.add(contactData.name)
                    }

                    localValid = true
                    ids.add(contactData.id)
                    keyword.add("@" + keyMention + contactData.id)
                    unknownContact.add(false)
                    if (contactData.isBlocking == 0) {
                        if (contactData.isAvailable) {
                            status.add(getString(R.string.online_status))
                        } else {
                            var statusText = ""
                            val lastSeen = contactData.lastSeen
                            if (lastSeen != 3000L) {
                                if (lastSeen == 0L) {
                                    statusText = ""
                                } else {
                                    statusText = getString(R.string.last_seen_status).replace(
                                        "%TIME%",
                                        lastSeen.convertToDateTime()
                                    )
                                }
                            }
                            status.add(statusText)
                        }
                    }

                }
            }

            viewModel.currentGroup.value?.members?.forEach { User ->

                var isCheckContact = false

                ids.forEach {
                    isCheckContact = User.id == it
                }
                if (!isCheckContact) {
                    if (srcMessage.contains("@" + keyMention + User.id)) {
                        if (User.id == user.id) {
                            msg = msg.replace("@" + keyMention + User.id, "@" + "Anda")
                            translatedMsg = translatedMsg.replace("@" + keyMention + User.id, "@" + "Anda")
                            length.add(4)
                            names.add("Anda")
                        } else {
                            msg = msg.replace("@" + keyMention + User.id, "@" + User.phone)
                            translatedMsg = translatedMsg.replace("@" + keyMention + User.id, "@" + User.phone)
                            length.add(User.phone.length)
                            names.add(User.phone)
                        }
                        localValid = true
                        ids.add(User.id)
                        keyword.add("@" + keyMention + User.id)
                        unknownContact.add(true)
                        status.add("")
                    }
                }

            }

            chatDetail.message = msg
            chatDetail.messageTranslated = translatedMsg
            chatDetail.mentionIds = ids
            chatDetail.mentionLength = length
            chatDetail.mentionName = names
            chatDetail.mentionKeyword = keyword
            chatDetail.mentionIsUnknownContact = unknownContact
            chatDetail.mentionStatus = status
        }

        return chatDetail
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onBackPressed() {
        if (isSearchViewVisible()) {
            hideSearchView()
            return
        }

        if (repliedChat != null) {
            binding.replyCancelButton.callOnClick()
            return
        }

        viewModel.resetXmppJid()
        super.onBackPressed()
        MediaPlayerUtils.releaseMediaPlayer()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_view)?.let {
            it.title = getString(
                if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                    if (viewModel.isUnknownContact) {
                        R.string.add_contact
                    } else {
                        R.string.view_contact
                    }
                } else {
                    R.string.view_group
                }
            )
        }

        menu?.findItem(R.id.action_mute)?.let {
            var found = false
            viewModel.contactLocal?.forEach {
                if (it.id == targetId) {
                    found = true
                }
            }

            if (!found) {
                menu?.findItem(R.id.action_mute).isVisible = false
            }

            if (isLeftGroup == 1 && chatType == ChatType.GROUP.id){
                menu?.findItem(R.id.action_voice).isEnabled = false
                menu?.findItem(R.id.action_video).isEnabled = false
            }

            val isMuted = if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                viewModel.currentContact?.isMuted
            } else {
                viewModel.currentGroup.value?.isMuted
            }

            it.title = getString(
                if (isMuted == 1) {
                    R.string.unmute
                } else {
                    R.string.mute
                }
            )
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_view -> {
                if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                    if (viewModel.isUnknownContact) {
                        val intent = Intent(ContactsContract.Intents.Insert.ACTION)
                        intent.setType(ContactsContract.RawContacts.CONTENT_TYPE)
                        intent.putExtra(
                            ContactsContract.Intents.Insert.PHONE,
                            viewModel.currentContact?.phone ?: ""
                        )
                        startActivityForResult(intent, REQUEST_CODE_ADD_CONTACT)
                    } else {
                        val intent = Intent(this, FriendProfileActivity::class.java)
                        intent.putExtra(CHAT_TARGET_ID, targetId)
                        intent.putExtra(CHAT_TARGET_TYPE, chatType)
                        intent.putExtra(CHAT_ONLINE_STATUS, onlineStatusText)

                        startActivity(intent)
                    }
                } else {
                    val intent = Intent(this, GroupInfoActivity::class.java)
                    intent.putExtra(GROUP_ID, targetId)
                    startActivity(intent)
                }
            }
            R.id.action_voice -> {
                viewModel.getCallParticipants(CallInvitationType.AUDIO)
            }
            R.id.action_video -> {
                viewModel.getCallParticipants(CallInvitationType.VIDEO)
            }
            R.id.action_search -> {
                binding.searchAppBar.visible()
                binding.sendMessageLayout.gone()
                binding.searchEditText.focusAndShowKeyboard()
                index = 0
                viewModel.getChatDetail(1000)
            }
            R.id.action_view_files -> {
                val intent = Intent(this, FilesActivity::class.java)
                intent.putExtra(CHAT_TARGET_ID, targetId)
                startActivity(intent)
            }
            R.id.action_mute -> {
                if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                    viewModel.currentContact?.let {
                        if (it.isMuted == 1) {
                            viewModel.unMuteContact()
                        } else {
                            showMuteDialog()
                        }
                    }
                } else {
                    viewModel.currentGroup.value?.let {
                        if (it.isMuted == 1) {
                            viewModel.unMuteGroup()
                        } else {
                            showMuteDialog()
                        }
                    }
                }
            }
            R.id.action_wallpaper -> showWallpaperDialog()
        }

        return super.onOptionsItemSelected(item)
    }

    private fun setKeyboardVisibilityListener(onKeyboardVisibilityListener: OnKeyboardVisibilityListener) {
        val parentView = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        parentView.viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            private var alreadyOpen = false
            private val defaultKeyboardHeightDP = 100
            private val EstimatedKeyboardDP = defaultKeyboardHeightDP + 48
            private val rect: Rect = Rect()

            override fun onGlobalLayout() {
                val estimatedKeyboardHeight = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    EstimatedKeyboardDP.toFloat(),
                    parentView.resources.displayMetrics
                ).toInt()
                parentView.getWindowVisibleDisplayFrame(rect)
                val heightDiff: Int = parentView.rootView.height - (rect.bottom - rect.top)
                val isShown = heightDiff >= estimatedKeyboardHeight
                if (isShown == alreadyOpen) {
                    return
                }
                alreadyOpen = isShown
                onKeyboardVisibilityListener.onVisibilityChanged(isShown)
            }
        })
    }

    override fun onVisibilityChanged(visible: Boolean) {
        if (visible && binding.messageEditText.length() > 0) {
            if (!isSearchViewVisible()) {
                binding.chatRecyclerView.scrollToPosition(0)
            }
            binding.rightButton.setImageResource(R.drawable.ic_send_chat)
            binding.rightButton.tag = 2
        } else {
            binding.rightButton.setImageResource(R.drawable.ic_microphone)
            binding.rightButton.tag = 1
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try{
            if (requestCode == REQUEST_CODE_ADD_CONTACT) {
                viewModel.syncPhoneBookContact(this)
            } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_CONTACT) {
                val result = data?.extras?.getParcelableArrayList<Contact>("data")
                val contactString = contactListToString(result, this)!!
                viewModel.insertChatDetail(
                    ChatDetail(
                        mediaType = MediaType.CONTACT,
                        message = "Kontak",
                        mediaContent = contactString
                    ), repliedChat, this, null, currentGroup
                )

                hideAttachmentView()
            } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_LOCATION) {
                val loc = data?.extras?.getString("latlong")
                val caption = data?.extras?.getString("caption")
                val isLive = data?.extras?.getBoolean("isLive")
                val placeTitle = data?.extras?.getString("placeTitle", "")
                val placeDetail = data?.extras?.getString("placeDetail", "")
                if (isLive!!) {
                    val liveDuration = data.extras?.getLong("liveDuration")
                    viewModel.insertChatDetail(
                        ChatDetail(
                            mediaType = MediaType.LIVE_LOCATION,
                            message = caption!!,
                            mediaContent = loc!!,
                            expiredLiveLocationDate = System.currentTimeMillis().plus(liveDuration!!)
                        ), repliedChat, this, null, currentGroup
                    )
                } else {
                    viewModel.insertChatDetail(
                        ChatDetail(
                            mediaType = MediaType.LOCATION,
                            message = placeTitle!!,
                            mediaContent = loc!!,
                            originalDocumentName = placeDetail!!,
                        ), repliedChat, this, null, currentGroup
                    )
                }

                hideAttachmentView()
            } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_TAKE_CAMERA) {
                val returnValue = data?.getStringArrayListExtra(Pix.IMAGE_RESULTS)
                returnValue?.forEach {
                    startActivityForResult(
                        Intent(
                            this,
                            PreviewAttachActivity::class.java
                        ).apply {
                            putExtra("data", returnValue)
                        }, REQUEST_CODE_PREVIEW
                    )
                }

                hideAttachmentView()
            } else if (resultCode == Activity.RESULT_OK &&
                requestCode == REQUEST_CODE_OPEN_GALLERY || requestCode == REQUEST_CODE_OPEN_GALLERY_WALLPAPER
            ) {
                if (requestCode == REQUEST_CODE_OPEN_GALLERY_WALLPAPER) {

                    val imageUri = data?.data
                    val imageBitmap = ExifUtils.getRotatedBitmap(this, imageUri).resizeByWidth(600)
                    val imageFile = createFilename(externalCacheDir, "wallpaper")
                    convertBitmapToFile(imageFile, imageBitmap)

                    val accountSettings = viewModel.accountSettings ?: return
                    viewModel.updateWallpaperSettings(accountSettings, imageFile)
                    return
                }

                val uri = data?.data
                val mimeType = uri?.getMimeType(this)

                if (mimeType!!.contains(MimeTypes.BASE_TYPE_VIDEO)) {
                    val file = uri.toFiles(this, Constant.GLIM_VIDEO_DIR, MediaType.VIDEO)
                    startActivityForResult(
                        Intent(
                            this,
                            PreviewAttachActivity::class.java
                        ).apply {
                            putExtra("data", arrayListOf(file?.absolutePath))
                        }, REQUEST_CODE_PREVIEW
                    )
                } else {
                    val file = data.data?.toFiles(this, Constant.GLIM_IMAGE_DIR, MediaType.PHOTO)
                    startActivityForResult(
                        Intent(
                            this,
                            PreviewAttachActivity::class.java
                        ).apply {
                            putExtra("data", arrayListOf(file?.absolutePath))
                        }, REQUEST_CODE_PREVIEW
                    )
                }

                hideAttachmentView()
            } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_OPEN_DOCUMENT) {
                val file = data?.data?.toFiles(this, Constant.GLIM_DOCUMENT_DIR, MediaType.FILE)
                viewModel.insertChatDetailWithAttach(
                    ChatDetail(
                        mediaType = MediaType.FILE,
                        message = "File",
                        mediaContentLocal = file!!.absolutePath,
                        originalDocumentName = file.name,
                        documentSize = file.length()
                    ), repliedChat, this, null, currentGroup
                )

                hideAttachmentView()
            } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_OPEN_AUDIO) {
                val filename = data?.getStringExtra("filename") ?: ""
                val file = data?.data?.toFiles(this, Constant.GLIM_AUDIO_DIR, filename)
                viewModel.insertChatDetailWithAttach(
                    ChatDetail(
                        mediaType = MediaType.AUDIO,
                        message = "Audio",
                        mediaContentLocal = file!!.absolutePath,
                        originalDocumentName = filename
                    ), repliedChat, this, null, currentGroup
                )

                hideAttachmentView()
            } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_PREVIEW) {
                val contentType = data?.getStringExtra("contentType")
                val path = data?.getStringExtra("path")
                val caption = data?.getStringExtra("caption")
                if (contentType == "video/*") {
                    viewModel.insertChatDetailWithAttach(
                        ChatDetail(
                            mediaType = MediaType.VIDEO,
                            message = caption.toString(),
                            mediaContentLocal = path.toString()
                        ), repliedChat, this, null, currentGroup
                    )
                } else {
                    viewModel.insertChatDetailWithAttach(
                        ChatDetail(
                            mediaType = MediaType.PHOTO,
                            message = caption.toString(),
                            mediaContentLocal = path.toString()
                        ), repliedChat, this, null, currentGroup
                    )
                }
            }
            if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_FORWARD) {
                if (data?.getStringExtra("multiple_contact") != null) {
                    val contactString = data.getStringExtra("multiple_contact")
                    val listContact = stringToContactList(contactString)
                    val listStanzaIds = data.getStringArrayExtra(Constant.CHAT_FORWARDED_STANZAS)
                    viewModel.sendForwardedMessagesMultiple(listStanzaIds, listContact)

                } else {
                    finish()
                }

            }

        } catch (e: Exception){
            //nothing todo
        }

    }


    override fun onDeleteChat(chats: ArrayList<ChatDetail>) {
        if (chats.isNotEmpty()) {
            val cancelText = resources.getString(R.string.delete_chat_confirm_cancel)
            val confirmText = resources.getString(R.string.delete_chat_confirm_ok)
            val confirmationMsg: String = if (chats.size > 1) {
                //multiple chats
                resources.getString(R.string.delete_multiple_chat_confirm)
                    .replace("%COUNT%", chats.size.toString())
            } else {
                //single chat
                if (chats[0].direction == ChatDirection.SELF) {
                    resources.getString(R.string.delete_self_chat_confirm)
                } else {
                    resources.getString(R.string.delete_single_chat_confirm)
                        .replace("%NAME%", chats[0].name)
                }
            }
            val clonedArr = chats.clone() //deep copy
            showConfirmationDialog(confirmationMsg, cancelText, confirmText, {}) {
                //Deletion confirmed
                viewModel.deleteChat(clonedArr as ArrayList<ChatDetail>)
                scrollListener.resetState()
            }
        }
    }

    override fun onCopyChat(chats: ArrayList<ChatDetail>) {
        var clipboardText = ""
        if (chats.size == 1) {
            clipboardText = chats.first().message
        } else if (chats.size > 1) {
            for (chat in chats) {
                val senderName =
                    if (chat.direction == ChatDirection.SELF) viewModel.currentUser?.name else chat.name
                clipboardText += "[" + convertTime(
                    chat.createdDate,
                    "MM/dd hh:mm"
                ) + "]" + " " + senderName + ":" + chat.message + "\n"
            }
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = ClipData.newPlainText("glim_copy", clipboardText)
        clipboard.setPrimaryClip(clip)

    }

    override fun onBookmarkChat(chats: List<ChatDetail>, isAllBookmarked: Boolean) {
        if (chats.size == 1) {
            viewModel.setBookmarkChat(chats, !chats.first().isBookmarked)
        } else if (chats.size > 1) {
            viewModel.setBookmarkChat(chats, !isAllBookmarked)
        }
    }

    override fun onReplyChat(chatDetail: ChatDetail) {
        repliedChat = chatDetail
        binding.replyNameTextView.text = if (chatDetail.direction == ChatDirection.SELF) {
            "Anda"
        } else {
            if (chatDetail.type == ChatType.PERSONAL) chatDetail.name else chatDetail.groupSenderName
        }
        binding.replyMessageTextView.text = chatDetail.getReplyContent(this)
        when (chatDetail.mediaType) {
            MediaType.PHOTO -> {
                binding.ivPhoto.visible()
                Glide.with(this).load(File(chatDetail.mediaContentLocal))
                    .signature(ObjectKey(signatureDate(DAY))).into(binding.ivPhoto)
            }
            MediaType.VIDEO -> {
                binding.ivPhoto.visible()
                Glide.with(this).load(File(chatDetail.mediaContentLocal))
                    .signature(ObjectKey(signatureDate(DAY))).into(binding.ivPhoto)
            }
            else -> {
                binding.ivPhoto.gone()
            }
        }
        binding.layoutReply.visible()
    }

    override fun onMessageInfo(chat: ChatDetail) {
        val messageInfoIntent = Intent(this, MessageInfoActivity::class.java)
        messageInfoIntent.putExtra("stanza_id", chat.stanzaId)
        startActivity(messageInfoIntent)
    }

    override fun onForwardChat(chats: ArrayList<ChatDetail>) {
        val intent = Intent(this, ForwardSelectContactActivity::class.java)
        intent.putExtra("data", resources.getString(R.string.forward))
        intent.putExtra("stanza_ids", chats.map { it.stanzaId }.toTypedArray())
        startActivityForResult(intent, REQUEST_CODE_FORWARD)
    }

    override fun onReUploadAttach(chat: ChatDetail) {
        viewModel.reuploadAttachment(File(chat.mediaContentLocal), chat, baseContext)
    }

    override fun onDownloadAttach(chat: ChatDetail) {
        checkWriteStoragePermission(chat)
    }

    private fun downloadAttach(chat: ChatDetail) {
        viewModel.downloadChatMedia(this, chat) { isSuccess, message ->
            Log.d("GLIM_ATTACHMENT", "Attachment download failed $message")
            if (!isSuccess) {
                adapter.notifyDataSetChanged()
                showErrorDialog(
                    getString(R.string.alert_title),
                    getString(R.string.download_attachment_failed)
                )
            }
        }
    }

    // Chat Search
    private fun isSearchViewVisible(): Boolean = binding.searchAppBar.visibility == View.VISIBLE

    fun hideSearchView() {
        binding.searchAppBar.gone()
        binding.sendMessageLayout.visible()
        binding.searchEditText.hideSoftKeyboard()
    }

    private fun searchPrevious(query: String) {
        if (listChat.isEmpty()) return
        isPreviousSearch = false
        var isFound = false

        for (i in index until listChat.size-1) {
            val data = listChat[i]

            if (data.message.contains(query, true) && data.direction != ChatDirection.HEADER) {
                index = i
                isFound = true
                break
            }
        }

        if (isFound) {
            binding.chatRecyclerView.scrollToPosition(index)
            adapter.highlightSearchItem(index)
            index++
            if (index >= listChat.size - 1){
                index = 0
            }
        } else {
            if (index == 0){
                onSearchNotFound()
            } else{
                index = 0
                searchPrevious(query)
            }
        }
    }

    private fun searchNext(query: String) {
        if (listChat.isEmpty()) return
        isPreviousSearch = false
        var isFound = false

        for (i in index downTo 0) {
            val data = listChat[i]

            if (data.message.contains(query, true) && data.direction != ChatDirection.HEADER) {
                index = i
                isFound = true
                break
            }
        }

        if (isFound) {
            binding.chatRecyclerView.scrollToPosition(index)
            adapter.highlightSearchItem(index)
            index--
            if (index < 0){
                index = listChat.size - 1
            }
        } else {
            if (index == listChat.size - 1){
                onSearchNotFound()
            } else{
                index = listChat.size - 1
                searchNext(query)
            }
        }
    }

    private fun onSearchNotFound() {
        isPreviousSearch = false
        Toast.makeText(applicationContext, getString(R.string.not_found), Toast.LENGTH_SHORT).show()
    }

    private fun goToChatWithStanzaId(stanzaId: String) {
        if (listChat.isEmpty()) return

        var index = -1
        for (i in 0 until listChat.size) {
            if (listChat[i].stanzaId == stanzaId) {
                index = i
                break
            }
        }

        val chatRecyclerView = binding.chatRecyclerView
        if (index >= 0) {
            targetStanzaId = ""
            chatRecyclerView.scrollToPosition(index)
            adapter.highlightSearchItem(index)
            return
        }

        val lastItemPosition = listChat.size - 1

        chatRecyclerView.scrollToPosition(lastItemPosition)

    }


    private fun showMuteDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
            dialog.setContentView(R.layout.dialog_mute_notification)
        } else {
            dialog.setContentView(R.layout.dialog_mute_notification_group)
        }

        val radioGroup = dialog.findViewById<RadioGroup>(R.id.radio)
        radioGroup?.setOnCheckedChangeListener { group, checkedId ->
            val rd = group.findViewById<RadioButton>(checkedId)
            val mutedUntil = rd.tag.toString().toInt()
            if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                viewModel.muteContact(mutedUntil)
            } else {
                viewModel.muteGroup(mutedUntil)
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showWallpaperDialog() {
        val accountSettings = viewModel.accountSettings ?: return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_select_image)
        dialog.setCancelable(true)
        val btnGallery = dialog.findViewById<ImageView>(R.id.btnGallery)
        btnGallery?.setOnClickListener {
            checkGalleryPermission(true)
            dialog.dismiss()
        }
        val btnDefault = dialog.findViewById<ImageView>(R.id.btnDefault)
        btnDefault?.setOnClickListener {
            viewModel.updateWallpaperSettings(
                accountSettings,
                getFileFromAsset(applicationContext, "wallpaper.png")
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onStopShared(chat: ChatDetail) {
        viewModel.updateExpiredDate(chat)
    }


    private fun blockedDialog(message: String) {
        val dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(
                "Buka Blokir"
            ) { dialog, which ->
                viewModel.unblockContact()
                showLoading()
                dialog.dismiss()
            }
            .setNegativeButton(
                "Batal"
            ) { dialog, which ->
                dialog.dismiss()
            }
        dialog?.show()
    }

    private fun showNoInternetDialog(message: String) {
        val dialog = AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(
                        "OK"
                ) { dialog, which ->
                    dialog.dismiss()
                }
        dialog?.show()
    }

    private fun setUserStatusText(isAvailable: Boolean, isTyping: Boolean, typingUserName: String) {
        onlineStatusText = ""

        if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
            //can't see online and last seen when contact disable last seen
            if (viewModel.currentContact?.setting?.lastSeen != 3){
                //can't see online and last seen when contact blocking
                if (viewModel.currentContact?.isBlocking == 0) {
                    if (isAvailable) {
                        onlineStatusText = getString(R.string.online_status)
                        binding.subTitleTextView.text = onlineStatusText
                        binding.subTitleTextView.visibility = View.VISIBLE
                    } else {
                        val lastSeen = viewModel.currentContact?.lastSeen ?: 0L * 1000
                        if (lastSeen != 3000L) {
                            if (lastSeen == 0L) {
                                onlineStatusText = ""
                            } else {
                                onlineStatusText = getString(R.string.last_seen_status).replace(
                                    "%TIME%",
                                    lastSeen.convertToDateTime()
                                )
                            }
                        }
                        binding.subTitleTextView.visibility = View.GONE
                    }
                }
            }
        } else {
            binding.subTitleTextView.text = getString(R.string.tap_to_see_group)
            binding.subTitleTextView.visibility = View.VISIBLE

        }

        if (isTyping) {
            if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
                if (viewModel.currentContact?.isBlocking != 1) {
                    binding.subTitleTextView.text = getString(R.string.is_typing)
                    binding.subTitleTextView.visibility = View.VISIBLE
                }
            } else {
                binding.subTitleTextView.text =
                    getString(R.string.is_typing_with_name).replace("%USER_NAME%", typingUserName)
                binding.subTitleTextView.visibility = View.VISIBLE
            }
        }
    }

    fun setTypingStatus(isTyping: Boolean) {
        Log.d("TYPING-PERSONAL", "setTypingStatus $isTyping")
        if (ChatType.fromInt(chatType) == ChatType.PERSONAL) {
            //not send typing when contact is blocked
            if (viewModel.currentContact?.isBlocking == 0) XMPPService.instance.setStateTyping(
                isTyping
            )
        } else {
            if (viewModel.currentGroup.value != null && viewModel.currentUser != null) {
                XMPPService.sendGroupTypingNotif(
                    viewModel.currentGroup.value!!,
                    viewModel.currentUser!!,
                    isTyping
                )
            }
        }
    }

    override fun onTapReply(chat: ChatDetail) {
        viewModel.getChatDetail(1000)
        if (chat.replyStanzaId.isEmpty()) {
            viewModel.getStatusLocalById(chat.replyName)
            return
        }

        listChat.forEach {
            if (chat.replyStanzaId == it.stanzaId) {
                binding.chatRecyclerView.scrollToPosition(listChat.indexOf(it))
                adapter.highlightSearchItem(listChat.indexOf(it))
            }
        }
    }

    override fun onTranslateChat(chat: ChatDetail) {
        var userLanguage = "id"
        viewModel.accountSettings?.let { userLanguage = it.userLanguage }
        viewModel.translateChat(chat, userLanguage)
    }

    fun loadWallpaper() {
        val accountSettingsWallpaper = viewModel.accountSettings
        Glide.with(applicationContext)
            .load(accountSettingsWallpaper?.chatWallpaper)
            .signature(ObjectKey(signatureDate(DAY)))
            .error(assets.open("wallpaper.png"))
            .into(binding.wallpaperImageView)
    }

    private fun stopChatTone() {
        try {
            toneMediaPlayer.release()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun playChatTone() {
        stopChatTone()
        toneMediaPlayer = MediaPlayer.create(applicationContext, R.raw.chat_tone)
        toneMediaPlayer.start()
    }

    private fun checkVoiceRecordingPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            startRecording()
            return
        }

        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        if (isPermissionsAllowed(permissions)) {
            startRecording()
        } else {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_RECORD_AUDIO)
        }
    }

    private fun isPermissionsAllowed(permissions: Array<String>): Boolean {
        permissions.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    private fun showDeniedAudioPermissionDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(getString(R.string.audio_permission_required))
            .setCancelable(false)
            .setPositiveButton(R.string.continues) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
        builder.create()
        builder.show()
    }

    private fun showAttachmentView() {
        binding.attachmentLayout.visible()
        binding.attachOutsideLayout.visible()
    }

    private fun hideAttachmentView() {
        binding.attachmentLayout.gone()
        binding.attachOutsideLayout.gone()
    }
}

interface OnKeyboardVisibilityListener {
    fun onVisibilityChanged(visible: Boolean)
}