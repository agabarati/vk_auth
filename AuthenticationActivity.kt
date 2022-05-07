package com.ironapp.millionare.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import com.android.volley.AuthFailureError
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.ironapp.millionare.R
import com.ironapp.millionare.data.AuthLiveDataViewModel
import com.ironapp.millionare.databinding.ActivityAuthenticationBinding
import com.ironapp.millionare.db.AppDatabase
import com.ironapp.millionare.db.Task
import com.ironapp.millionare.util.MyConstants
import com.vk.api.sdk.VK
import com.vk.api.sdk.auth.VKAuthenticationResult
import com.vk.api.sdk.auth.VKScope
import kotlinx.android.synthetic.main.activity_authentication.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.util.*

class AuthenticationActivity : AppCompatActivity() {


    val authLauncher = VK.login(this) { result : VKAuthenticationResult ->
        when (result) {
            is VKAuthenticationResult.Success -> {
                // User passed authorization
            }
            is VKAuthenticationResult.Failed -> {
                // User didn't pass authorization
            }
        }
    }

    // [START declare_auth]
    private lateinit var auth: FirebaseAuth
    // [END declare_auth]

    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var callbackManager: CallbackManager

    val db = FirebaseFirestore.getInstance()

    private lateinit var mDb: AppDatabase

    private lateinit var viewModel: AuthLiveDataViewModel

    lateinit var binding : ActivityAuthenticationBinding

    private lateinit var builder : AlertDialog.Builder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mDb = AppDatabase.getAppDatabase(this.baseContext)
        //Костыль, для создания базы данных
        doAsync {
            mDb.taskDao().getRandomTask()
        }

        viewModel = ViewModelProvider(this).get(AuthLiveDataViewModel::class.java)

        // Obtain binding
        binding  = DataBindingUtil.setContentView(this, R.layout.activity_authentication)

        // Bind layout with ViewModel
        binding.viewmodel = viewModel

        // LiveData needs the lifecycle owner
        binding.lifecycleOwner = this

        //TODO change startActivity
        binding.signInGoogleButton.setOnClickListener {
            signInGoogle()
        }

        // [START config_signin]
        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        // [END config_signin]

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // [START initialize_auth]
        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()
        // [END initialize_auth]

        //FACEBOOK BLOCK
        callbackManager = CallbackManager.Factory.create()

        signInFacebookButton.setReadPermissions("email", "public_profile")
        signInFacebookButton.registerCallback(callbackManager, object :
            FacebookCallback<LoginResult> {
            override fun onSuccess(loginResult: LoginResult) {
                Log.d(TAG, "facebook:onSuccess:$loginResult")
                handleFacebookAccessToken(loginResult.accessToken)
            }

            override fun onCancel() {
                Log.d(TAG, "facebook:onCancel")
                // [START_EXCLUDE]
                updateUI(false)
                // [END_EXCLUDE]
            }

            override fun onError(error: FacebookException) {
                Log.d(TAG, "facebook:onError", error)
                // [START_EXCLUDE]
                updateUI(false)
                // [END_EXCLUDE]
            }
        })

        builder = AlertDialog.Builder(this)

    }

    // [START on_start_check_user]
    public override fun onStart() {
        super.onStart()
        // Check if user is signed in (non-null) and update UI accordingly.
        if (isInternetAvailable(this)) {
            loadActiveTasks()
            updateUI(false)
        } else {
            updateUI(false)
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        var result = false
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networkCapabilities = connectivityManager.activeNetwork ?: return false
            val actNw =
                connectivityManager.getNetworkCapabilities(networkCapabilities) ?: return false
            result = when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } else {
            connectivityManager.run {
                connectivityManager.activeNetworkInfo?.run {
                    result = when (type) {
                        ConnectivityManager.TYPE_WIFI -> true
                        ConnectivityManager.TYPE_MOBILE -> true
                        ConnectivityManager.TYPE_ETHERNET -> true
                        else -> false
                    }

                }
            }
        }

        return result
    }

    private fun loadActiveTasks() {
        Log.d(TAG, "loading tasks")
        // Instantiate the RequestQueue.
        val queue by lazy { Volley.newRequestQueue(applicationContext) }
        val sr = object: StringRequest(
            Method.POST, MyConstants.URL_ALL_ACTIVE_TASKS,
            Listener { response -> Log.e("HttpClient",
                "success! response: $response"
            )
                val gson = GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss").setPrettyPrinting().create()
                viewModel.allActiveTasksList = gson.fromJson(response.toString(), object : TypeToken<List<Task>>() {}.type)
                doAsync {
                    mDb.taskDao().deleteAll()
                    uiThread {
                        doAsync {
                            mDb.taskDao().insert(viewModel.allActiveTasksList)
                            uiThread {
                                Log.d(TAG, "tasks loaded and was add to DB")
                            }
                        }
                    }
                }
            },
            Response.ErrorListener {
                    error -> Log.e("HttpClient", "error: $error")

            }) {
            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["password"] = MyConstants.PARAM_ALL_ACTIVE_TASKS_PASSWORD
                return params
            }
        }
        queue.add(sr)
    }


    // [START GOOGLE onactivityresult]
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                // Google Sign In was successful, authenticate with Firebase
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account!!)
            } catch (e: ApiException) {
                // Google Sign In failed, update UI appropriately
                Log.w(TAG, "Google sign in failed", e)
                // [START_EXCLUDE]
                updateUI(true)
                // [END_EXCLUDE]
            }
        } else {
            // Pass the activity result back to the Facebook SDK
            callbackManager.onActivityResult(requestCode, resultCode, data)
        }

    }
    // [END onactivityresult]

    // [START auth_with_google]
    private fun firebaseAuthWithGoogle(acct: GoogleSignInAccount) {
        Log.d(TAG, "firebaseAuthWithGoogle:" + acct.id!!)
        // [START_EXCLUDE silent]
        //showProgressDialog()
        // [END_EXCLUDE]

        val credential = GoogleAuthProvider.getCredential(acct.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "signInWithCredential:success")
                    updateUI(true)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    //Snackbar.make(main_layout, "Authentication Failed.", Snackbar.LENGTH_SHORT).show()
                    updateUI(false)
                }

                // [START_EXCLUDE]
                //hideProgressDialog()
                // [END_EXCLUDE]
            }
    }
    // [END auth_with_google]

    // [START signin]
    private fun signInGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent,
            RC_SIGN_IN
        )
    }
    // [END signin]

    public fun vkAuth() {
        //authLauncher.launch(arrayListOf(VKScope.WALL, VKScope.PHOTOS))
        authLauncher.launch(arrayListOf(VKScope.WALL, VKScope.PHOTOS))
    }

    private fun showPrivacyDialog() {
        val mDialogView = LayoutInflater.from(this).inflate(R.layout.privacy_dialog_content, null)

        builder.setTitle (getString(R.string.policyDialogTitle))
        builder.setMessage (getString(R.string.policyDialogMessage))
        builder.setView(mDialogView)

        builder.setPositiveButton("Разы дæн") { dialog, which ->
            openGame()
        }

        builder.setOnCancelListener {
            Log.d(TAG, "on cancel dialog")
            logOut()
        }

        builder.show()

//        signInGoogle()
    }

    private fun updateUI(needToShowPrivacy: Boolean) {
        // hideProgressDialog()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            //TODO CHANGE TO CHECK Add player to DB if not exists
             if (needToShowPrivacy) {
                showPrivacyDialog()
            } else {
                openGame()
            }
        }
    }

    private fun openGame() {
        checkOrAddPlayerPostRequest()
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun logOut() {
        auth.signOut()

        try {
            LoginManager.getInstance().logOut()
        } catch (ex:Exception) {

        }
    }

    // [START auth_with_facebook]
    private fun handleFacebookAccessToken(token: AccessToken) {
        Log.d(TAG, "handleFacebookAccessToken:$token")
        // [START_EXCLUDE silent]
        //showProgressDialog()
        // [END_EXCLUDE]

        val credential = FacebookAuthProvider.getCredential(token.token)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "signInWithCredential:success")
                    updateUI(true)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    Toast.makeText(baseContext, "Authentication failed.",
                        Toast.LENGTH_SHORT).show()
                    updateUI(true)
                }

                // [START_EXCLUDE]
                //hideProgressDialog()
                // [END_EXCLUDE]
            }
    }
    // [END auth_with_facebook]

    fun checkOrAddPlayerPostRequest() {
        // Instantiate the RequestQueue.
        val queue = Volley.newRequestQueue(this)
        val sr = object: StringRequest(
            Method.POST, MyConstants.URL_CHECK_OR_CREATE_PLAYER,
            Listener { response -> Log.e("HttpClient",
                "success! response: $response"

            )
            },
            Response.ErrorListener { error -> Log.e("HttpClient", "error: $error") }) {
            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                auth.currentUser?.uid?.let { params.put("userId", it) }
                auth.currentUser?.displayName?.let { params.put("username", it) }
                auth.currentUser?.email?.let { params.put("email", it) }
                params["playedGames"] = "0"
                return params
            }
        }
        queue.add(sr)
    }

    fun openPrivacyAuth(v: View) {
        startActivity(Intent(this, PrivacyActivity::class.java))
    }

    companion object {
        private const val TAG = "AuthenticationActivity"
        private const val RC_SIGN_IN = 9001
    }

}

