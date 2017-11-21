package com.delaroystudios.inappsubscription;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.delaroystudios.inappsubscription.util.IabBroadcastReceiver;
import com.delaroystudios.inappsubscription.util.IabHelper;
import com.delaroystudios.inappsubscription.util.IabResult;
import com.delaroystudios.inappsubscription.util.Inventory;
import com.delaroystudios.inappsubscription.util.Purchase;

import java.util.ArrayList;
import java.util.List;

import static com.delaroystudios.inappsubscription.Constants.SKU_DELAROY_MONTHLY;
import static com.delaroystudios.inappsubscription.Constants.SKU_DELAROY_SIXMONTH;
import static com.delaroystudios.inappsubscription.Constants.SKU_DELAROY_THREEMONTH;
import static com.delaroystudios.inappsubscription.Constants.SKU_DELAROY_YEARLY;
import static com.delaroystudios.inappsubscription.Constants.base64EncodedPublicKey;

public class MainActivity extends AppCompatActivity implements IabBroadcastReceiver.IabBroadcastListener,
        DialogInterface.OnClickListener {
    // Debug tag, for logging
    static final String TAG = "MainActivity";

    // Does the user have an active subscription to the delaroy plan?
    boolean mSubscribedToDelaroy = false;

    // Will the subscription auto-renew?
    boolean mAutoRenewEnabled = false;

    // Tracks the currently owned subscription, and the options in the Manage dialog
    String mDelaroySku = "";
    String mFirstChoiceSku = "";
    String mSecondChoiceSku = "";
    String mThirdChoiceSku = "";
    String mFourthChoiceSku = "";

    // Used to select between subscribing on a monthly, three month, six month or yearly basis
    String mSelectedSubscriptionPeriod = "";

    // SKU for our subscription

    // (arbitrary) request code for the purchase flow
    static final int RC_REQUEST = 10001;


    // The helper object
    IabHelper mHelper;

    // Provides purchase notification while this app is running
    IabBroadcastReceiver mBroadcastReceiver;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.subscribe_layout);

        // Create the helper, passing it our context and the public key to verify signatures with
        Log.d(TAG, "Creating IAB helper.");
        mHelper = new IabHelper(this, base64EncodedPublicKey);

        // enable debug logging (for a production application, you should set this to false).
        mHelper.enableDebugLogging(true);

        // Start setup. This is asynchronous and the specified listener
        // will be called once setup completes.
        Log.d(TAG, "Starting setup.");
        mHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
            public void onIabSetupFinished(IabResult result) {
                Log.d(TAG, "Setup finished.");

                if (!result.isSuccess()) {
                    // Oh noes, there was a problem.
                    complain("Problem setting up in-app billing: " + result);
                    return;
                }

                // Have we been disposed of in the meantime? If so, quit.
                if (mHelper == null) return;

                mBroadcastReceiver = new IabBroadcastReceiver(MainActivity.this);
                IntentFilter broadcastFilter = new IntentFilter(IabBroadcastReceiver.ACTION);
                registerReceiver(mBroadcastReceiver, broadcastFilter);

                // IAB is fully set up. Now, let's get an inventory of stuff we own.
                Log.d(TAG, "Setup successful. Querying inventory.");
                try {
                    mHelper.queryInventoryAsync(mGotInventoryListener);
                } catch (IabHelper.IabAsyncInProgressException e) {
                    complain("Error querying inventory. Another async operation in progress.");
                }
            }
        });
    }

    // Listener that's called when we finish querying the items and subscriptions we own
    IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
            Log.d(TAG, "Query inventory finished.");

            // Have we been disposed of in the meantime? If so, quit.
            if (mHelper == null) return;

            // Is it a failure?
            if (result.isFailure()) {
                complain("Failed to query inventory: " + result);
                return;
            }

            Log.d(TAG, "Query inventory was successful.");


            // First find out which subscription is auto renewing
            Purchase delaroyMonthly = inventory.getPurchase(SKU_DELAROY_MONTHLY);
            Purchase delaroyThreeMonth = inventory.getPurchase(SKU_DELAROY_THREEMONTH);
            Purchase delaroySixMonth = inventory.getPurchase(SKU_DELAROY_SIXMONTH);
            Purchase delaroyYearly = inventory.getPurchase(SKU_DELAROY_YEARLY);
            if (delaroyMonthly != null && delaroyMonthly.isAutoRenewing()) {
                mDelaroySku = SKU_DELAROY_MONTHLY;
                mAutoRenewEnabled = true;
            } else if (delaroyThreeMonth != null && delaroyThreeMonth.isAutoRenewing()) {
                mDelaroySku = SKU_DELAROY_THREEMONTH;
                mAutoRenewEnabled = true;
            } else if (delaroySixMonth != null && delaroySixMonth.isAutoRenewing()){
                mDelaroySku = SKU_DELAROY_SIXMONTH;
                mAutoRenewEnabled = true;
            } else if (delaroyYearly != null && delaroyYearly.isAutoRenewing()){
                mDelaroySku = SKU_DELAROY_YEARLY;
                mAutoRenewEnabled = true;
            }

            else {
                mDelaroySku = "";
                mAutoRenewEnabled = false;
            }

            // The user is subscribed if either subscription exists, even if neither is auto
            // renewing
            mSubscribedToDelaroy = (delaroyMonthly != null && verifyDeveloperPayload(delaroyMonthly))
                    || (delaroyThreeMonth != null && verifyDeveloperPayload(delaroyThreeMonth))
                    || (delaroySixMonth != null && verifyDeveloperPayload(delaroySixMonth))
                    || (delaroyYearly != null && verifyDeveloperPayload(delaroyYearly));
            Log.d(TAG, "User " + (mSubscribedToDelaroy ? "HAS" : "DOES NOT HAVE")
                    + " infinite gas subscription.");

            updateUi();
            setWaitScreen(false);
            Log.d(TAG, "Initial inventory query finished; enabling main UI.");
        }
    };

    @Override
    public void receivedBroadcast() {
        // Received a broadcast notification that the inventory of items has changed
        Log.d(TAG, "Received broadcast notification. Querying inventory.");
        try {
            mHelper.queryInventoryAsync(mGotInventoryListener);
        } catch (IabHelper.IabAsyncInProgressException e) {
            complain("Error querying inventory. Another async operation in progress.");
        }
    }

    // "Subscribe to delaroy" button clicked. Explain to user, then start purchase
    // flow for subscription.
    public void onSubscribeButtonClicked(View arg0) {
        if (!mHelper.subscriptionsSupported()) {
            complain("Subscriptions not supported on your device yet. Sorry!");
            return;
        }

        CharSequence[] options;
        if (!mSubscribedToDelaroy || !mAutoRenewEnabled) {
            // Both subscription options should be available
            options = new CharSequence[4];
            options[0] = getString(R.string.subscription_period_monthly);
            options[1] = getString(R.string.subscription_period_threemonth);
            options[2] = getString(R.string.subscription_period_sixmonth);
            options[3] = getString(R.string.subscription_period_yearly);
            mFirstChoiceSku = SKU_DELAROY_MONTHLY;
            mSecondChoiceSku = SKU_DELAROY_THREEMONTH;
            mThirdChoiceSku = SKU_DELAROY_SIXMONTH;
            mFourthChoiceSku = SKU_DELAROY_YEARLY;
        } else {
            // This is the subscription upgrade/downgrade path, so only one option is valid
            options = new CharSequence[3];
            if (mDelaroySku.equals(SKU_DELAROY_MONTHLY)) {
                // Give the option to upgrade below
                options[0] = getString(R.string.subscription_period_threemonth);
                options[1] = getString(R.string.subscription_period_sixmonth);
                options[2] = getString(R.string.subscription_period_yearly);
                mFirstChoiceSku = SKU_DELAROY_THREEMONTH;
                mSecondChoiceSku = SKU_DELAROY_SIXMONTH;
                mThirdChoiceSku = SKU_DELAROY_YEARLY;
            } else if (mDelaroySku.equals(SKU_DELAROY_THREEMONTH)){
                // Give the option to upgrade or downgrade below
                options[0] = getString(R.string.subscription_period_monthly);
                options[1] = getString(R.string.subscription_period_sixmonth);
                options[2] = getString(R.string.subscription_period_yearly);
                mFirstChoiceSku = SKU_DELAROY_MONTHLY;
                mSecondChoiceSku = SKU_DELAROY_SIXMONTH;
                mThirdChoiceSku = SKU_DELAROY_YEARLY;
            }else if (mDelaroySku.equals(SKU_DELAROY_SIXMONTH)){
                // Give the option to upgrade or downgrade below
                options[0] = getString(R.string.subscription_period_monthly);
                options[1] = getString(R.string.subscription_period_threemonth);
                options[2] = getString(R.string.subscription_period_yearly);
                mFirstChoiceSku = SKU_DELAROY_MONTHLY;
                mSecondChoiceSku = SKU_DELAROY_THREEMONTH;
                mThirdChoiceSku = SKU_DELAROY_YEARLY;

            }else{
                // Give the option to upgrade or downgrade below
                options[0] = getString(R.string.subscription_period_monthly);
                options[1] = getString(R.string.subscription_period_threemonth);
                options[2] = getString(R.string.subscription_period_sixmonth);
                mFirstChoiceSku = SKU_DELAROY_THREEMONTH;
                mSecondChoiceSku = SKU_DELAROY_SIXMONTH;
                mThirdChoiceSku = SKU_DELAROY_YEARLY;
            }
            mFourthChoiceSku = "";
        }

        int titleResId;
        if (!mSubscribedToDelaroy) {
            titleResId = R.string.subscription_period_prompt;
        } else if (!mAutoRenewEnabled) {
            titleResId = R.string.subscription_resignup_prompt;
        } else {
            titleResId = R.string.subscription_update_prompt;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(titleResId)
                .setSingleChoiceItems(options, 0 /* checkedItem */, this)
                .setPositiveButton(R.string.subscription_prompt_continue, this)
                .setNegativeButton(R.string.subscription_prompt_cancel, this);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    public void onClick(DialogInterface dialog, int id) {
        if (id == 0 /* First choice item */) {
            mSelectedSubscriptionPeriod = mFirstChoiceSku;
        } else if (id == 1 /* Second choice item */) {
            mSelectedSubscriptionPeriod = mSecondChoiceSku;
        }else if (id == 2) {
            mSelectedSubscriptionPeriod = mThirdChoiceSku;
        }else if (id == 3){
            mSelectedSubscriptionPeriod = mFourthChoiceSku;
        } else if (id == DialogInterface.BUTTON_POSITIVE /* continue button */) {

            String payload = "";

            if (TextUtils.isEmpty(mSelectedSubscriptionPeriod)) {
                // The user has not changed from the default selection
                mSelectedSubscriptionPeriod = mFirstChoiceSku;
            }

            List<String> oldSkus = null;
            if (!TextUtils.isEmpty(mDelaroySku)
                    && !mDelaroySku.equals(mSelectedSubscriptionPeriod)) {
                // The user currently has a valid subscription, any purchase action is going to
                // replace that subscription
                oldSkus = new ArrayList<String>();
                oldSkus.add(mDelaroySku);
            }

            setWaitScreen(true);
            try {
                mHelper.launchPurchaseFlow(this, mSelectedSubscriptionPeriod, IabHelper.ITEM_TYPE_SUBS,
                        oldSkus, RC_REQUEST, mPurchaseFinishedListener, payload);
            } catch (IabHelper.IabAsyncInProgressException e) {
                complain("Error launching purchase flow. Another async operation in progress.");
                setWaitScreen(false);
            }
            // Reset the dialog options
            mSelectedSubscriptionPeriod = "";
            mFirstChoiceSku = "";
            mSecondChoiceSku = "";
        } else if (id != DialogInterface.BUTTON_NEGATIVE) {
            // There are only four buttons, this should not happen
            Log.e(TAG, "Unknown button clicked in subscription dialog: " + id);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data);
        if (mHelper == null) return;

        // Pass on the activity result to the helper for handling
        if (!mHelper.handleActivityResult(requestCode, resultCode, data)) {
            // not handled, so handle it ourselves (here's where you'd
            // perform any handling of activity results not related to in-app
            // billing...
            super.onActivityResult(requestCode, resultCode, data);
        }
        else {
            Log.d(TAG, "onActivityResult handled by IABUtil.");
        }
    }

    /** Verifies the developer payload of a purchase. */
    boolean verifyDeveloperPayload(Purchase p) {
        String payload = p.getDeveloperPayload();


        return true;
    }

    // Callback for when a purchase is finished
    IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener = new IabHelper.OnIabPurchaseFinishedListener() {
        public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
            Log.d(TAG, "Purchase finished: " + result + ", purchase: " + purchase);

            // if we were disposed of in the meantime, quit.
            if (mHelper == null) return;

            if (result.isFailure()) {
                complain("Error purchasing: " + result);
                setWaitScreen(false);
                return;
            }
            if (!verifyDeveloperPayload(purchase)) {
                complain("Error purchasing. Authenticity verification failed.");
                setWaitScreen(false);
                return;
            }

            Log.d(TAG, "Purchase successful.");

            if (purchase.getSku().equals(SKU_DELAROY_MONTHLY)
                    || purchase.getSku().equals(SKU_DELAROY_THREEMONTH)
                    || purchase.getSku().equals(SKU_DELAROY_SIXMONTH)
                    || purchase.getSku().equals(SKU_DELAROY_YEARLY)){
                // bought the rasbita subscription
                Log.d(TAG, "Delaroy subscription purchased.");
                alert("Thank you for subscribing to Delaroy!");
                mSubscribedToDelaroy = true;
                mAutoRenewEnabled = purchase.isAutoRenewing();
                mDelaroySku = purchase.getSku();
                updateUi();
                setWaitScreen(false);
            }
        }
    };

    // Called when consumption is complete
    IabHelper.OnConsumeFinishedListener mConsumeFinishedListener = new IabHelper.OnConsumeFinishedListener() {
        public void onConsumeFinished(Purchase purchase, IabResult result) {
            Log.d(TAG, "Consumption finished. Purchase: " + purchase + ", result: " + result);

            updateUi();
            setWaitScreen(false);
            Log.d(TAG, "End consumption flow.");
        }
    };


    // We're being destroyed. It's important to dispose of the helper here!
    @Override
    public void onDestroy() {
        super.onDestroy();

        // very important:
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
        }

        // very important:
        Log.d(TAG, "Destroying helper.");
        if (mHelper != null) {
            mHelper.disposeWhenFinished();
            mHelper = null;
        }
    }

    // updates UI to reflect model
    public void updateUi() {

        ImageView subscribeButton = (ImageView) findViewById(R.id.rasbita_subscribe);
        if (mSubscribedToDelaroy) {
            Intent intent = new Intent(this, DashboardActivity.class);
            startActivity(intent);
            finish();
        } else {
            // The user does not have rabista subscription"
            subscribeButton.setImageResource(R.drawable.subscribe);
        }


    }

    // Enables or disables the "please wait" screen.
    void setWaitScreen(boolean set) {
        findViewById(R.id.screen_main).setVisibility(set ? View.GONE : View.VISIBLE);
    }

    void complain(String message) {
        Log.e(TAG, "**** Delaroy Error: " + message);
        alert("Error: " + message);
    }

    void alert(String message) {
        AlertDialog.Builder bld = new AlertDialog.Builder(this);
        bld.setMessage(message);
        bld.setNeutralButton("OK", null);
        Log.d(TAG, "Showing alert dialog: " + message);
        bld.create().show();
    }


}

