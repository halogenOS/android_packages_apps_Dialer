/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.incallui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallCapabilities;
import android.telecomm.PhoneAccount;
import android.telecomm.PhoneAccountHandle;
import android.telecomm.StatusHints;
import android.telecomm.TelecommManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.incallui.AudioModeProvider.AudioModeListener;
import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.incallui.InCallPresenter.InCallDetailsListener;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.InCallPresenter.InCallStateListener;
import com.android.incallui.InCallPresenter.IncomingCallListener;
import com.google.common.base.Preconditions;

/**
 * Presenter for the Call Card Fragment.
 * <p>
 * This class listens for changes to InCallState and passes it along to the fragment.
 */
public class CallCardPresenter extends Presenter<CallCardPresenter.CallCardUi>
        implements InCallStateListener, AudioModeListener, IncomingCallListener,
        InCallDetailsListener {

    private static final String TAG = CallCardPresenter.class.getSimpleName();
    private static final long CALL_TIME_UPDATE_INTERVAL = 1000; // in milliseconds

    private Call mPrimary;
    private Call mSecondary;
    private ContactCacheEntry mPrimaryContactInfo;
    private ContactCacheEntry mSecondaryContactInfo;
    private CallTimer mCallTimer;
    private Context mContext;
    private TelecommManager mTelecommManager;

    public CallCardPresenter() {
        // create the call timer
        mCallTimer = new CallTimer(new Runnable() {
            @Override
            public void run() {
                updateCallTime();
            }
        });
    }


    public void init(Context context, Call call) {
        mContext = Preconditions.checkNotNull(context);

        // Call may be null if disconnect happened already.
        if (call != null) {
            mPrimary = call;

            // start processing lookups right away.
            if (!call.isConferenceCall()) {
                startContactInfoSearch(call, true, call.getState() == Call.State.INCOMING);
            } else {
                updateContactEntry(null, true, true);
            }
        }
    }

    @Override
    public void onUiReady(CallCardUi ui) {
        super.onUiReady(ui);

        AudioModeProvider.getInstance().addListener(this);

        // Contact search may have completed before ui is ready.
        if (mPrimaryContactInfo != null) {
            updatePrimaryDisplayInfo(mPrimaryContactInfo, isConference(mPrimary));
        }

        // Register for call state changes last
        InCallPresenter.getInstance().addListener(this);
        InCallPresenter.getInstance().addIncomingCallListener(this);
        InCallPresenter.getInstance().addDetailsListener(this);
    }

    @Override
    public void onUiUnready(CallCardUi ui) {
        super.onUiUnready(ui);

        // stop getting call state changes
        InCallPresenter.getInstance().removeListener(this);
        InCallPresenter.getInstance().removeIncomingCallListener(this);
        InCallPresenter.getInstance().removeDetailsListener(this);

        AudioModeProvider.getInstance().removeListener(this);

        mPrimary = null;
        mPrimaryContactInfo = null;
        mSecondaryContactInfo = null;
    }

    @Override
    public void onIncomingCall(InCallState state, Call call) {
        // same logic should happen as with onStateChange()
        onStateChange(state, CallList.getInstance());
    }

    @Override
    public void onStateChange(InCallState state, CallList callList) {
        Log.d(this, "onStateChange() " + state);
        final CallCardUi ui = getUi();
        if (ui == null) {
            return;
        }

        Call primary = null;
        Call secondary = null;

        if (state == InCallState.INCOMING) {
            primary = callList.getIncomingCall();
        } else if (state == InCallState.OUTGOING) {
            primary = callList.getOutgoingCall();

            // getCallToDisplay doesn't go through outgoing or incoming calls. It will return the
            // highest priority call to display as the secondary call.
            secondary = getCallToDisplay(callList, null, true);
        } else if (state == InCallState.INCALL) {
            primary = getCallToDisplay(callList, null, false);
            secondary = getCallToDisplay(callList, primary, true);
        }

        Log.d(this, "Primary call: " + primary);
        Log.d(this, "Secondary call: " + secondary);

        final boolean primaryChanged = !areCallsSame(mPrimary, primary);
        final boolean secondaryChanged = !areCallsSame(mSecondary, secondary);
        mSecondary = secondary;
        mPrimary = primary;

        if (primaryChanged && mPrimary != null) {
            // primary call has changed
            mPrimaryContactInfo = ContactInfoCache.buildCacheEntryFromCall(mContext, mPrimary,
                    mPrimary.getState() == Call.State.INCOMING);
            updatePrimaryDisplayInfo(mPrimaryContactInfo, isConference(mPrimary));
            maybeStartSearch(mPrimary, true);
        }

        if (mSecondary == null) {
            // Secondary call may have ended.  Update the ui.
            mSecondaryContactInfo = null;
            updateSecondaryDisplayInfo(false);
        } else if (secondaryChanged) {
            // secondary call has changed
            mSecondaryContactInfo = ContactInfoCache.buildCacheEntryFromCall(mContext, mSecondary,
                    mSecondary.getState() == Call.State.INCOMING);
            updateSecondaryDisplayInfo(mSecondary.isConferenceCall());
            maybeStartSearch(mSecondary, false);
        }

        // Start/Stop the call time update timer
        if (mPrimary != null && mPrimary.getState() == Call.State.ACTIVE) {
            Log.d(this, "Starting the calltime timer");
            mCallTimer.start(CALL_TIME_UPDATE_INTERVAL);
        } else {
            Log.d(this, "Canceling the calltime timer");
            mCallTimer.cancel();
            ui.setPrimaryCallElapsedTime(false, null);
        }

        // Set the call state
        int callState = Call.State.IDLE;
        if (mPrimary != null) {
            callState = mPrimary.getState();

            getUi().setCallState(callState, mPrimary.getDisconnectCause(), getConnectionLabel(),
                    getConnectionIcon(), getGatewayNumber());

            String currentNumber = getNumberFromHandle(mPrimary.getHandle());
            if (PhoneNumberUtils.isEmergencyNumber(currentNumber)) {
                String callbackNumber = getSubscriptionNumber();
                setCallbackNumber(callbackNumber, true);
            }
        } else {
            getUi().setCallState(callState, DisconnectCause.NOT_VALID, null, null, null);
        }

        // Hide/show the contact photo depending if this is a video call
        if (mPrimary != null) {
            getUi().setPhotoVisible(!mPrimary.isVideoCall());
        }

        final boolean enableEndCallButton = Call.State.isConnected(callState) &&
                callState != Call.State.INCOMING && mPrimary != null;
        getUi().setEndCallButtonEnabled(enableEndCallButton);
    }

    @Override
    public void onDetailsChanged(android.telecomm.Call.Details details) {
        getUi().setCallDetails(details);

        if (mPrimary != null) {
            setCallbackNumberIfSet(details);
        }
    }

    private String getSubscriptionNumber() {
        // If it's an emergency call, and they're not populating the callback number,
        // then try to fall back to the phone sub info (to hopefully get the SIM's
        // number directly from the telephony layer).
        PhoneAccountHandle accountHandle = mPrimary.getAccountHandle();
        if (accountHandle != null) {
            TelecommManager mgr = TelecommManager.from(mContext);
            PhoneAccount account = mgr.getPhoneAccount(accountHandle);
            if (account != null) {
                return account.getSubscriptionNumber();
            }
        }
        return null;
    }

    private void setCallbackNumberIfSet(android.telecomm.Call.Details details) {
        String callbackNumber = null;

        String currentNumber = getNumberFromHandle(mPrimary.getHandle());
        boolean isEmergencyCall = PhoneNumberUtils.isEmergencyNumber(currentNumber);

        StatusHints statusHints = details.getStatusHints();
        if (statusHints != null) {
            Bundle extras = statusHints.getExtras();
            if (extras != null) {
                callbackNumber = extras.getString(
                        TelecommManager.EXTRA_CALL_BACK_NUMBER, null);

                if (isEmergencyCall) {
                    callbackNumber = getSubscriptionNumber();
                }
            } else {
                Log.d(this, "No extras; not updating callback number");
            }
        } else {
            Log.d(this, "No status hints; not updating callback number");
        }

        setCallbackNumber(callbackNumber, isEmergencyCall);
    }

    private void setCallbackNumber(String callbackNumber, boolean isEmergencyCall) {
        if (TextUtils.isEmpty(callbackNumber)) {
            Log.d(this, "No callback number; aborting");
            return;
        }

        String simNumber = TelephonyManager.from(mContext).getLine1Number();
        if (!PhoneNumberUtils.compare(callbackNumber, simNumber) && !isEmergencyCall) {
            Log.d(this, "Numbers are the same; not showing the callback number");
            return;
        }
        getUi().setCallbackNumber(callbackNumber, isEmergencyCall);
    }

    @Override
    public void onAudioMode(int mode) {
    }

    @Override
    public void onSupportedAudioMode(int mask) {
    }

    @Override
    public void onMute(boolean muted) {
    }

    public void updateCallTime() {
        final CallCardUi ui = getUi();

        if (ui == null || mPrimary == null || mPrimary.getState() != Call.State.ACTIVE) {
            if (ui != null) {
                ui.setPrimaryCallElapsedTime(false, null);
            }
            mCallTimer.cancel();
        } else {
            final long callStart = mPrimary.getConnectTimeMillis();
            final long duration = System.currentTimeMillis() - callStart;
            ui.setPrimaryCallElapsedTime(true, DateUtils.formatElapsedTime(duration / 1000));
        }
    }

    public void phoneAccountClicked() {
        if (mPrimary == null) {
            return;
        }
        TelecommAdapter.getInstance().phoneAccountClicked(mPrimary.getId());
    }

    private boolean areCallsSame(Call call1, Call call2) {
        if (call1 == null && call2 == null) {
            return true;
        } else if (call1 == null || call2 == null) {
            return false;
        }

        // otherwise compare call Ids
        return call1.getId().equals(call2.getId());
    }

    private void maybeStartSearch(Call call, boolean isPrimary) {
        // no need to start search for conference calls which show generic info.
        if (call != null && !call.isConferenceCall()) {
            startContactInfoSearch(call, isPrimary, call.getState() == Call.State.INCOMING);
        }
    }

    /**
     * Starts a query for more contact data for the save primary and secondary calls.
     */
    private void startContactInfoSearch(final Call call, final boolean isPrimary,
            boolean isIncoming) {
        final ContactInfoCache cache = ContactInfoCache.getInstance(mContext);

        cache.findInfo(call, isIncoming, new ContactInfoCacheCallback() {
                @Override
                public void onContactInfoComplete(String callId, ContactCacheEntry entry) {
                    updateContactEntry(entry, isPrimary, false);
                    if (entry.name != null) {
                        Log.d(TAG, "Contact found: " + entry);
                    }
                    if (entry.contactUri != null) {
                        CallerInfoUtils.sendViewNotification(mContext, entry.contactUri);
                    }
                }

                @Override
                public void onImageLoadComplete(String callId, ContactCacheEntry entry) {
                    if (getUi() == null) {
                        return;
                    }
                    if (entry.photo != null) {
                        if (mPrimary != null && callId.equals(mPrimary.getId())) {
                            getUi().setPrimaryImage(entry.photo);
                        }
                    }
                }
            });
    }

    private static boolean isConference(Call call) {
        return call != null && call.isConferenceCall();
    }

    private static boolean isGenericConference(Call call) {
        return call != null && call.can(CallCapabilities.GENERIC_CONFERENCE);
    }

    private void updateContactEntry(ContactCacheEntry entry, boolean isPrimary,
            boolean isConference) {
        if (isPrimary) {
            mPrimaryContactInfo = entry;
            updatePrimaryDisplayInfo(entry, isConference);
        } else {
            mSecondaryContactInfo = entry;
            updateSecondaryDisplayInfo(isConference);
        }
    }

    /**
     * Get the highest priority call to display.
     * Goes through the calls and chooses which to return based on priority of which type of call
     * to display to the user. Callers can use the "ignore" feature to get the second best call
     * by passing a previously found primary call as ignore.
     *
     * @param ignore A call to ignore if found.
     */
    private Call getCallToDisplay(CallList callList, Call ignore, boolean skipDisconnected) {

        // Active calls come second.  An active call always gets precedent.
        Call retval = callList.getActiveCall();
        if (retval != null && retval != ignore) {
            return retval;
        }

        // Disconnected calls get primary position if there are no active calls
        // to let user know quickly what call has disconnected. Disconnected
        // calls are very short lived.
        if (!skipDisconnected) {
            retval = callList.getDisconnectingCall();
            if (retval != null && retval != ignore) {
                return retval;
            }
            retval = callList.getDisconnectedCall();
            if (retval != null && retval != ignore) {
                return retval;
            }
        }

        // Then we go to background call (calls on hold)
        retval = callList.getBackgroundCall();
        if (retval != null && retval != ignore) {
            return retval;
        }

        // Lastly, we go to a second background call.
        retval = callList.getSecondBackgroundCall();

        return retval;
    }

    private void updatePrimaryDisplayInfo(ContactCacheEntry entry, boolean isConference) {
        Log.d(TAG, "Update primary display " + entry);
        final CallCardUi ui = getUi();
        if (ui == null) {
            // TODO: May also occur if search result comes back after ui is destroyed. Look into
            // removing that case completely.
            Log.d(TAG, "updatePrimaryDisplayInfo called but ui is null!");
            return;
        }

        final boolean isGenericConf = isGenericConference(mPrimary);
        if (entry != null) {
            final String name = getNameForCall(entry);
            final String number = getNumberForCall(entry);
            final boolean nameIsNumber = name != null && name.equals(entry.number);
            ui.setPrimary(number, name, nameIsNumber, entry.label,
                    entry.photo, isConference, isGenericConf, entry.isSipCall);
        } else {
            ui.setPrimary(null, null, false, null, null, isConference, isGenericConf, false);
        }

    }

    private void updateSecondaryDisplayInfo(boolean isConference) {

        final CallCardUi ui = getUi();
        if (ui == null) {
            return;
        }

        final boolean isGenericConf = isGenericConference(mSecondary);
        if (mSecondaryContactInfo != null) {
            Log.d(TAG, "updateSecondaryDisplayInfo() " + mSecondaryContactInfo);
            final String nameForCall = getNameForCall(mSecondaryContactInfo);

            final boolean nameIsNumber = nameForCall != null && nameForCall.equals(
                    mSecondaryContactInfo.number);
            ui.setSecondary(true /* show */, nameForCall, nameIsNumber, mSecondaryContactInfo.label,
                    getSecondaryCallProviderLabel(), getSecondaryCallProviderIcon(),
                    isConference, isGenericConf);
        } else {
            // reset to nothing so that it starts off blank next time we use it.
            ui.setSecondary(false, null, false, null, null, null, isConference, isGenericConf);
        }
    }

    /**
     * Returns the gateway number for any existing outgoing call.
     */
    private String getGatewayNumber() {
        if (hasOutgoingGatewayCall()) {
            return getNumberFromHandle(mPrimary.getGatewayInfo().getGatewayHandle());
        }
        return null;
    }

    /**
     * Return the Drawable object of the icon to display to the left of the connection label.
     */
    private Drawable getConnectionIcon() {
        PhoneAccountHandle accountHandle = mPrimary.getAccountHandle();
        if (accountHandle != null) {
            PhoneAccount account = getTelecommManager().getPhoneAccount(accountHandle);
            if (account != null) {
                return account.getIcon(mContext);
            }
        }
        return null;
    }

    /**
     * Returns the label (line of text above the number/name) for any given call.
     * For example, "calling via [Account/Google Voice]" for outgoing calls.
     */
    private String getConnectionLabel() {
        if (hasOutgoingGatewayCall() && getUi() != null) {
            // Return the label for the gateway app on outgoing calls.
            final PackageManager pm = mContext.getPackageManager();
            try {
                ApplicationInfo info = pm.getApplicationInfo(
                        mPrimary.getGatewayInfo().getGatewayProviderPackageName(), 0);
                return pm.getApplicationLabel(info).toString();
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(this, "Gateway Application Not Found.", e);
                return null;
            }
        }
        PhoneAccountHandle accountHandle = mPrimary.getAccountHandle();
        if (accountHandle != null) {
            PhoneAccount account = getTelecommManager().getPhoneAccount(accountHandle);
            if (account != null) {
                return getAccountLabel(account);
            }
        }
        return null;
    }

    private String getSecondaryCallProviderLabel() {
        PhoneAccountHandle accountHandle = mSecondary.getAccountHandle();
        if (accountHandle != null) {
            PhoneAccount account = getTelecommManager().getPhoneAccount(accountHandle);
            if (account != null) {
                return getAccountLabel(account);
            }
        }
        return null;
    }

    private Drawable getSecondaryCallProviderIcon() {
        PhoneAccountHandle accountHandle = mSecondary.getAccountHandle();
        if (accountHandle != null) {
            PhoneAccount account = getTelecommManager().getPhoneAccount(accountHandle);
            if (account != null) {
                return account.getIcon(mContext);
            }
        }
        return null;
    }

    private boolean hasOutgoingGatewayCall() {
        // We only display the gateway information while DIALING so return false for any othe
        // call state.
        // TODO: mPrimary can be null because this is called from updatePrimaryDisplayInfo which
        // is also called after a contact search completes (call is not present yet).  Split the
        // UI update so it can receive independent updates.
        if (mPrimary == null) {
            return false;
        }
        return Call.State.isDialing(mPrimary.getState()) && mPrimary.getGatewayInfo() != null &&
                !mPrimary.getGatewayInfo().isEmpty();
    }

    /**
     * Gets the name to display for the call.
     */
    private static String getNameForCall(ContactCacheEntry contactInfo) {
        if (TextUtils.isEmpty(contactInfo.name)) {
            return contactInfo.number;
        }
        return contactInfo.name;
    }

    /**
     * Gets the number to display for a call.
     */
    private static String getNumberForCall(ContactCacheEntry contactInfo) {
        // If the name is empty, we use the number for the name...so dont show a second
        // number in the number field
        if (TextUtils.isEmpty(contactInfo.name)) {
            return contactInfo.location;
        }
        return contactInfo.number;
    }

    public void secondaryInfoClicked() {
        if (mSecondary == null) {
            Log.wtf(this, "Secondary info clicked but no secondary call.");
            return;
        }

        Log.i(this, "Swapping call to foreground: " + mSecondary);
        TelecommAdapter.getInstance().unholdCall(mSecondary.getId());
    }

    public void endCallClicked() {
        if (mPrimary == null) {
            return;
        }

        Log.i(this, "Disconnecting call: " + mPrimary);
        TelecommAdapter.getInstance().disconnectCall(mPrimary.getId());
    }

    private String getNumberFromHandle(Uri handle) {
        return handle == null ? "" : handle.getSchemeSpecificPart();
    }

    private String getAccountLabel(PhoneAccount account) {
        return account.getLabel() == null ? null : account.getLabel().toString();
    }

    public interface CallCardUi extends Ui {
        void setVisible(boolean on);
        void setPrimary(String number, String name, boolean nameIsNumber, String label,
                Drawable photo, boolean isConference, boolean isGeneric, boolean isSipCall);
        void setSecondary(boolean show, String name, boolean nameIsNumber, String label,
                String providerLabel, Drawable providerIcon, boolean isConference,
                boolean isGeneric);
        void setCallState(int state, int cause, String connectionLabel, Drawable connectionIcon,
                String gatewayNumber);
        void setPrimaryCallElapsedTime(boolean show, String duration);
        void setPrimaryName(String name, boolean nameIsNumber);
        void setPrimaryImage(Drawable image);
        void setPrimaryPhoneNumber(String phoneNumber);
        void setPrimaryLabel(String label);
        void setEndCallButtonEnabled(boolean enabled);
        void setCallbackNumber(String number, boolean isEmergencyCalls);
        void setCallDetails(android.telecomm.Call.Details details);
        void setPhotoVisible(boolean isVisible);
    }

    private TelecommManager getTelecommManager() {
        if (mTelecommManager == null) {
            mTelecommManager = TelecommManager.from(mContext);
        }
        return mTelecommManager;
    }
}
