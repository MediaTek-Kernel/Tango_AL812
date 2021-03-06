/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.internal.telephony.uicc;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.telephony.Rlog;
import android.content.res.Resources;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.gsm.SimTlv;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_IMSI;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_DEFAULT_NAME;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import android.app.ActivityManagerNative;
import android.os.UserHandle;
import static android.Manifest.permission.READ_PHONE_STATE;


import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import android.util.Log;//add by zhaizhanfeng for virtual voicemail at 150811

import android.content.BroadcastReceiver;
import com.mediatek.common.telephony.ITelephonyExt;
import com.mediatek.common.MPlugin;
import android.app.AlertDialog;
import android.os.PowerManager;
import android.view.WindowManager;
import android.content.DialogInterface;

import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;
import com.mediatek.internal.telephony.uicc.SvlteUiccUtils;

/**
 * {@hide}
 */
public class SIMRecords extends IccRecords {
    protected static final String LOG_TAG = "SIMRecords";

    private static final boolean CRASH_RIL = false;

    // ***** Instance Variables

    VoiceMailConstants mVmConfig;


    SpnOverride mSpnOverride;

    // ***** Cached SIM State; cleared on channel close

    private boolean mCallForwardingEnabled;


    /**
     * States only used by getSpnFsm FSM
     */
    private GetSpnFsmState mSpnState;

    /** CPHS service information (See CPHS 4.2 B.3.1.1)
     *  It will be set in onSimReady if reading GET_CPHS_INFO successfully
     *  mCphsInfo[0] is CPHS Phase
     *  mCphsInfo[1] and mCphsInfo[2] is CPHS Service Table
     */
    private byte[] mCphsInfo = null;
    boolean mCspPlmnEnabled = true;

    byte[] mEfMWIS = null;
    byte[] mEfCPHS_MWI =null;
    byte[] mEfCff = null;
    byte[] mEfCfis = null;

    byte[] mEfLi = null;
    byte[] mEfPl = null;

    int mSpnDisplayCondition;
    // Numeric network codes listed in TS 51.011 EF[SPDI]
    ArrayList<String> mSpdiNetworks = null;

    String mPnnHomeName = null;

    UsimServiceTable mUsimServiceTable;

    private String mSimImsi = null;
    @Override
    public String toString() {
        return "SimRecords: " + super.toString()
                + " mVmConfig" + mVmConfig
                + " mSpnOverride=" + "mSpnOverride"
                + " callForwardingEnabled=" + mCallForwardingEnabled
                + " spnState=" + mSpnState
                + " mCphsInfo=" + mCphsInfo
                + " mCspPlmnEnabled=" + mCspPlmnEnabled
                + " efMWIS=" + mEfMWIS
                + " efCPHS_MWI=" + mEfCPHS_MWI
                + " mEfCff=" + mEfCff
                + " mEfCfis=" + mEfCfis
                + " getOperatorNumeric=" + getOperatorNumeric();
    }

    // ***** Constants

    // From TS 51.011 EF[SPDI] section
    static final int TAG_SPDI = 0xA3;
    static final int TAG_SPDI_PLMN_LIST = 0x80;

    // Full Name IEI from TS 24.008
    static final int TAG_FULL_NETWORK_NAME = 0x43;

    // Short Name IEI from TS 24.008
    static final int TAG_SHORT_NETWORK_NAME = 0x45;

    // active CFF from CPHS 4.2 B.4.5
    static final int CFF_UNCONDITIONAL_ACTIVE = 0x0a;
    static final int CFF_UNCONDITIONAL_DEACTIVE = 0x05;
    static final int CFF_LINE1_MASK = 0x0f;
    static final int CFF_LINE1_RESET = 0xf0;

    // CPHS Service Table (See CPHS 4.2 B.3.1)
    private static final int CPHS_SST_MBN_MASK = 0x30;
    private static final int CPHS_SST_MBN_ENABLED = 0x30;

    // EF_CFIS related constants
    // Spec reference TS 51.011 section 10.3.46.
    private static final int CFIS_BCD_NUMBER_LENGTH_OFFSET = 2;
    private static final int CFIS_TON_NPI_OFFSET = 3;
    private static final int CFIS_ADN_CAPABILITY_ID_OFFSET = 14;
    private static final int CFIS_ADN_EXTENSION_ID_OFFSET = 15;

    // ***** Event Constants
    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_ICCID_DONE = 4;
    private static final int EVENT_GET_MBI_DONE = 5;
    private static final int EVENT_GET_MBDN_DONE = 6;
    private static final int EVENT_GET_MWIS_DONE = 7;
    private static final int EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE = 8;
    protected static final int EVENT_GET_AD_DONE = 9; // Admin data on SIM
    protected static final int EVENT_GET_MSISDN_DONE = 10;
    private static final int EVENT_GET_CPHS_MAILBOX_DONE = 11;
    private static final int EVENT_GET_SPN_DONE = 12;
    private static final int EVENT_GET_SPDI_DONE = 13;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_PNN_DONE = 15;
    protected static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;
    private static final int EVENT_SET_MBDN_DONE = 20;
    private static final int EVENT_SMS_ON_SIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;
    private static final int EVENT_GET_CFF_DONE = 24;
    private static final int EVENT_SET_CPHS_MAILBOX_DONE = 25;
    private static final int EVENT_GET_INFO_CPHS_DONE = 26;
    // private static final int EVENT_SET_MSISDN_DONE = 30; Defined in IccRecords as 30
    private static final int EVENT_SIM_REFRESH = 31;
    private static final int EVENT_GET_CFIS_DONE = 32;
    private static final int EVENT_GET_CSP_CPHS_DONE = 33;
    private static final int EVENT_GET_GID1_DONE = 34;
    private static final int EVENT_APP_LOCKED = 35;

    // Added by M begin
    private static final int EVENT_RADIO_AVAILABLE = 41;
    private static final int EVENT_GET_LI_DONE = 42;
    private static final int EVENT_GET_ELP_DONE = 43;
    private static final int EVENT_DUAL_IMSI_READY = 44;

    private static final int EVENT_QUERY_MENU_TITLE_DONE = 53;

    private static final int EVENT_GET_SIM_ECC_DONE = 102;
    private static final int EVENT_GET_USIM_ECC_DONE = 103;
    private static final int EVENT_GET_ALL_OPL_DONE = 104;
    private static final int EVENT_GET_CPHSONS_DONE = 105;
    private static final int EVENT_GET_SHORT_CPHSONS_DONE = 106;
    private static final int EVENT_QUERY_ICCID_DONE = 107;
    private static final int EVENT_RADIO_STATE_CHANGED = 201;
    private static final int EVENT_PHB_READY = 202;
    private static final int EVENT_EF_CSP_PLMN_MODE_BIT_CHANGED = 203; // ALPS00302698 ENS
    private static final int EVENT_GET_RAT_DONE = 204; // ALPS00302702 RAT balancing
    private static final int EVENT_QUERY_ICCID_DONE_FOR_HOT_SWAP = 205;
    private static final int EVENT_GET_NEW_MSISDN_DONE = 206;
    private static final int EVENT_GET_PSISMSC_DONE = 207; // USim authentication
    private static final int EVENT_GET_SMSP_DONE = 208; // USim authentication
    private static final int EVENT_GET_GBABP_DONE = 209;
    private static final int EVENT_GET_GBANL_DONE = 210;
    private static final int EVENT_CFU_IND = 211;
    private static final int EVENT_IMSI_REFRESH_QUERY = 212;
    private static final int EVENT_IMSI_REFRESH_QUERY_DONE = 213;
    private static final int EVENT_GET_EF_ICCID_DONE = 300;

    /* Remote SIM ME lock */
    private static final int EVENT_MELOCK_CHANGED = 400;
    // Added by M end

    // Lookup table for carriers known to produce SIMs which incorrectly indicate MNC length.

    private static final String[] MCCMNC_CODES_HAVING_3DIGITS_MNC = {
        "302370", "302720", "310260",
        "405025", "405026", "405027", "405028", "405029", "405030", "405031", "405032",
        "405033", "405034", "405035", "405036", "405037", "405038", "405039", "405040",
        "405041", "405042", "405043", "405044", "405045", "405046", "405047", "405750",
        "405751", "405752", "405753", "405754", "405755", "405756", "405799", "405800",
        "405801", "405802", "405803", "405804", "405805", "405806", "405807", "405808",
        "405809", "405810", "405811", "405812", "405813", "405814", "405815", "405816",
        "405817", "405818", "405819", "405820", "405821", "405822", "405823", "405824",
        "405825", "405826", "405827", "405828", "405829", "405830", "405831", "405832",
        "405833", "405834", "405835", "405836", "405837", "405838", "405839", "405840",
        "405841", "405842", "405843", "405844", "405845", "405846", "405847", "405848",
        "405849", "405850", "405851", "405852", "405853", "405875", "405876", "405877",
        "405878", "405879", "405880", "405881", "405882", "405883", "405884", "405885",
        "405886", "405908", "405909", "405910", "405911", "405912", "405913", "405914",
        "405915", "405916", "405917", "405918", "405919", "405920", "405921", "405922",
        "405923", "405924", "405925", "405926", "405927", "405928", "405929", "405930",
        "405931", "405932", "502142", "502143", "502145", "502146", "502147", "502148"
    };
    private static final String KEY_SIM_ID = "SIM_ID";

    // Added by M begin
    private boolean isValidMBI = false;
    private static final String ACTION_RESET_MODEM = "android.intent.action.sim.ACTION_RESET_MODEM";

    // ALPS00302702 RAT balancing
    private boolean mEfRatLoaded = false;
    private byte[] mEfRat = null;

    private static final String[] LANGUAGE_CODE_FOR_LP = {
        "de", "en", "it", "fr", "es", "nl", "sv", "da", "pt", "fi",
        "no", "el", "tr", "hu", "pl", "",
        "cs", "he", "ar", "ru", "is", "", "", "", "", "",
        "", "", "", "", "", ""
    };

    int mSlotId;
	private int mSimId;//add by zhaizhanfeng for virtual voicemail at 150811
	public boolean isSetByCountry = false;//add by zhaizhanfeng for virtual voicemail at 150811
    private ITelephonyExt mTelephonyExt;
    private BroadcastReceiver mSimReceiver;
    private BroadcastReceiver mSubReceiver;
    String cphsOnsl;
    String cphsOnss;
    private int iccIdQueryState = -1; // -1: init, 0: query error, 1: query successful
    private boolean hasQueryIccId;

    private int efLanguageToLoad = 0;
    private boolean mIsPhbEfResetDone = false;

    byte[] mEfSST = null;
    byte[] mEfELP = null;
    byte[] mEfPsismsc = null;
    byte[] mEfSmsp = null;

    static final String[] SIMRECORD_PROPERTY_RIL_PHB_READY  = {
        "gsm.sim.ril.phbready",
        "gsm.sim.ril.phbready.2",
        "gsm.sim.ril.phbready.3",
        "gsm.sim.ril.phbready.4"
    };

    static final String[] SIMRECORD_PROPERTY_RIL_PUK1  = {
        "gsm.sim.retry.puk1",
        "gsm.sim.retry.puk1.2",
        "gsm.sim.retry.puk1.3",
        "gsm.sim.retry.puk1.4",
    };
	//add by zhaizhanfeng for virtual voicemail at 150811 start
	private String[] SIM_RECORDS_PROPERTY_GID1_LIST = {
		"ril.gid1.sim1",
		"ril.gid1.sim2",
		"ril.gid1.sim3",
		"ril.gid1.sim4",
	};
	
	private String[] SIM_RECORDS_PROPERTY_EF_SST_LIST = {
		"ril.ef.sst.sim1",
		"ril.ef.sst.sim2",
		"ril.ef.sst.sim3",
		"ril.ef.sst.sim4",
	};
	 		
	private String[] SIM_RECORDS_PROPERTY_ICCID_LIST = {
		"ril.ef.iccid.sim1",
		"ril.ef.iccid.sim2",
		"ril.ef.iccid.sim3",
		"ril.ef.iccid.sim4",
	};	
	//add by zhaizhanfeng for virtual voicemail at 150811 end

    private String[] SIM_RECORDS_PROPERTY_ECC_LIST = {
        "ril.ecclist",
        "ril.ecclist1",
        "ril.ecclist2",
        "ril.ecclist3",
    };

    static final String PROPERTY_3G_SIM = "persist.radio.simswitch";
    private boolean mPhbReady = false;
    private boolean mPhbWaitSub = false;
    private boolean mSIMInfoReady = false;

    public static class OperatorName {
        public String sFullName;
        public String sShortName;
    }

    /*Operator list recode
    * include numeric mcc mnc code
    * and a range of LAC, the operator name index in PNN
    */
    public static class OplRecord {
        public String sPlmn;
        public int nMinLAC;
        public int nMaxLAC;
        public int nPnnIndex;
    }

    //Operator name listed in TS 51.011 EF[PNN] for plmn in operator list(EF[OPL])
    private ArrayList<OperatorName> mPnnNetworkNames = null;
    //Operator list in TS 51.011 EF[OPL]
    private ArrayList<OplRecord> mOperatorList = null;

    private String mSpNameInEfSpn = null; // MVNO-API

    private String mMenuTitleFromEf = null;

    //3g dongle
    private boolean isDispose = false;
    private static final int[] simServiceNumber = {
        1, 17, 51, 52, 54, 55, 56, 0, 12, 3, 0
    };

    private static final int[] usimServiceNumber = {
        0, 19, 45, 46, 48, 49, 51, 71, 12, 2, 0
    };

    private UiccCard mUiccCard;
    private UiccController mUiccController;
    private String mCdmaImsi;
    private String mGbabp;
    private ArrayList<byte[]> mEfGbanlList;
    private String[] mGbanl;

    private Phone mPhone;

    String mEfEcc = "";

    // Added by M end

    // ***** Constructor

    public SIMRecords(UiccCardApplication app, Context c, CommandsInterface ci) {
        super(app, c, ci);

        mSlotId = app.getSlotId();
        mUiccController = UiccController.getInstance();
        mUiccCard = mUiccController.getUiccCard(mSlotId);
        log("mUiccCard Instance = " + mUiccCard);
        if (DBG) log("SIMRecords construct");

        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            mPhone = SvlteUtils.getSvltePhoneProxy(mSlotId).getLtePhone();
        } else {
            mPhone = PhoneFactory.getPhone(app.getPhoneId());
        }

        mAdnCache = new AdnRecordCache(mFh, ci, app);
        ///M: Move UPBM code to here for phone restart event to contacts app.begin
        Intent intent = new Intent();
        intent.setAction("android.intent.action.ACTION_PHONE_RESTART");
        intent.putExtra("SimId", mSlotId);
        mContext.sendBroadcast(intent);
        ///M: end

        mVmConfig = new VoiceMailConstants();
        //mSpnOverride = new SpnOverride();
        mSpnOverride = SpnOverride.getInstance();

        mRecordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        mRecordsToLoad = 0;

        cphsOnsl = null;
        cphsOnss = null;
        hasQueryIccId = false;


        mCi.setOnSmsOnSim(this, EVENT_SMS_ON_SIM, null);
        mCi.registerForIccRefresh(this, EVENT_SIM_REFRESH, null);
        mCi.registerForPhbReady(this, EVENT_PHB_READY, null);
        /* register for CFU info flag notification */
        mCi.registerForCallForwardingInfo(this, EVENT_CFU_IND, null);
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForEfCspPlmnModeBitChanged(this, EVENT_EF_CSP_PLMN_MODE_BIT_CHANGED, null);
        mCi.registerForMelockChanged(this, EVENT_MELOCK_CHANGED, null);

        mCi.registerForImsiRefreshDone(this, EVENT_IMSI_REFRESH_QUERY, null);
        // Start off by setting empty state
        resetRecords();
        mParentApp.registerForReady(this, EVENT_APP_READY, null);
        mParentApp.registerForLocked(this, EVENT_APP_LOCKED, null);

        mSimReceiver = new SIMBroadCastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.mediatek.dm.LAWMO_WIPE");
        filter.addAction("action_pin_dismiss");
        filter.addAction("action_melock_dismiss");
        filter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        //filter.addAction(Intent.ACTION_LOCALE_CHANGED);  //ALPS00288486
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mContext.registerReceiver(mSimReceiver, filter);

        mSubReceiver = new SubBroadCastReceiver();
        IntentFilter subFilter = new IntentFilter();
        subFilter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        mContext.registerReceiver(mSubReceiver, subFilter);

        /** M: Bug Fix for ALPS02189616 */
        // register new receiver for RADIO_TECHNOLOGY_CHANGED
        mRTC = new RadioTechnologyChangedReceiver();
        IntentFilter rtcFilter = new IntentFilter();
        rtcFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        mContext.registerReceiver(mRTC, rtcFilter);

        // ALPS01099419, mAdnCache is needed before onUpdateIccAvailability.
        if (DBG) log("SIMRecords updateIccRecords");
        mPhone.getIccPhoneBookInterfaceManager().updateIccRecords(this);

        //ALPS00566446: Check if phb is ready or not, if phb was already ready, we won't wait for phb ready.
        if (isPhbReady()) {
            if (DBG) log("Phonebook is ready.");
            //broadcastPhbStateChangedIntent(mPhbReady);
            //modified by maolikui at 2015-09-19 start  
              broadcastPhbStateChangedIntent(isPhbReady()); 
            //modified by maolikui at 2015-09-19 end
        }

        try {
            mTelephonyExt = MPlugin.createInstance(ITelephonyExt.class.getName(), mContext);
        } catch (Exception e) {
            loge("Fail to create plug-in");
            e.printStackTrace();
        }

        if (DBG) log("SIMRecords X ctor this=" + this);
    }

    @Override
    public void dispose() {
        if (DBG) log("Disposing SIMRecords this=" + this);
        //Unregister for all events
        //3g dongle
        isDispose = true;
        mCi.unregisterForIccRefresh(this);
        mCi.unregisterForCallForwardingInfo(this);
        mCi.unSetOnSmsOnSim(this);
        mCi.unregisterForPhbReady(this);
        mCi.unregisterForRadioStateChanged(this);
        mCi.unregisterForEfCspPlmnModeBitChanged(this);
        mCi.unregisterForMelockChanged(this);
        mParentApp.unregisterForReady(this);
        mParentApp.unregisterForLocked(this);

        mContext.unregisterReceiver(mSimReceiver);
        mContext.unregisterReceiver(mSubReceiver);
        mPhbWaitSub = false;

        resetRecords();
        mAdnCache.reset();
        setPhbReady(false);
        mIccId = null;
        mImsi = null;
        super.dispose();
    }

    @Override
    protected void finalize() {
        if(DBG) log("finalized");
    }

    protected void resetRecords() {
        if (DBG) log("resetRecords");
        //mImsi = null;  //[ALPS00127136]
        mMsisdn = null;
        mVoiceMailNum = null;
        mMncLength = UNINITIALIZED;
        log("setting0 mMncLength" + mMncLength);
        //mIccId = null;
        // -1 means no EF_SPN found; treat accordingly.
        mSpnDisplayCondition = -1;
        mEfMWIS = null;
        mEfCPHS_MWI = null;
        mSpdiNetworks = null;
        mPnnHomeName = null;
        mGid1 = null;

        mAdnCache.reset();

        log("SIMRecords: onRadioOffOrNotAvailable set 'gsm.sim.operator.numeric' to operator=null");
        log("update icc_operator_numeric=" + null);
        mTelephonyManager.setSimOperatorNumericForPhone(mParentApp.getPhoneId(), "");
        mTelephonyManager.setSimOperatorNameForPhone(mParentApp.getPhoneId(), "");
        mTelephonyManager.setSimCountryIsoForPhone(mParentApp.getPhoneId(), "");
        // MTK-START
        setSystemProperty(PROPERTY_ICC_OPERATOR_IMSI, null);
        setSystemProperty(PROPERTY_ICC_OPERATOR_DEFAULT_NAME, null);
        // MTK-END

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        mRecordsRequested = false;

        // Because mMsisdn is changed
        mRecordsEventsRegistrants.notifyResult(EVENT_MSISDN);
    }


    //***** Public Methods

    /**
     * {@inheritDoc}
     */
    @Override
    public String getIMSI() {
        return mImsi;
    }

    @Override
    public String getMsisdnNumber() {
        return mMsisdn;
    }

    @Override
    public String getGid1() {
        return mGid1;
    }

    @Override
    public UsimServiceTable getUsimServiceTable() {
        return mUsimServiceTable;
    }
	//add by zhaizhanfeng for virtual voicemail at 150811 start  
    public String getFile() {
		String mFile = null;
		if(mSlotId == 0){
			 mFile = SystemProperties.get("ril.ef.sst.sim1");
		}else if(mSlotId == 1){
			 mFile = SystemProperties.get("ril.ef.sst.sim2");
		}
		Log.i("zhai","getFile: mSlotId = "+mSlotId+ ", mFile = "+ mFile);
		 return mFile;
	 }
	//add by zhaizhanfeng for virtual voicemail at 150811 end

    /**
     * Set subscriber number to SIM record
     *
     * The subscriber number is stored in EF_MSISDN (TS 51.011)
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (up to 10 characters)
     * @param number dailing nubmer (up to 20 digits)
     *        if the number starts with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    @Override
    public void setMsisdnNumber(String alphaTag, String number,
            Message onComplete) {

        // If the SIM card is locked by PIN, we will set EF_MSISDN fail.
        // In that case, msisdn and msisdnTag should not be update.
        mNewMsisdn = number;
        mNewMsisdnTag = alphaTag;

        if(DBG) log("Set MSISDN: " + mNewMsisdnTag + " " + /*mNewMsisdn*/ "xxxxxxx");


        AdnRecord adn = new AdnRecord(mNewMsisdnTag, mNewMsisdn);

        new AdnRecordLoader(mFh).updateEF(adn, EF_MSISDN, EF_EXT1, 1, null,
                obtainMessage(EVENT_SET_MSISDN_DONE, onComplete));
    }

    @Override
    public String getMsisdnAlphaTag() {
        return mMsisdnTag;
    }

    @Override
    public String getVoiceMailNumber() {
        log("getVoiceMailNumber " + mVoiceMailNum);
        return mVoiceMailNum;
    }

    /**
     * Set voice mail number to SIM record
     *
     * The voice mail number can be stored either in EF_MBDN (TS 51.011) or
     * EF_MAILBOX_CPHS (CPHS 4.2)
     *
     * If EF_MBDN is available, store the voice mail number to EF_MBDN
     *
     * If EF_MAILBOX_CPHS is enabled, store the voice mail number to EF_CHPS
     *
     * So the voice mail number will be stored in both EFs if both are available
     *
     * Return error only if both EF_MBDN and EF_MAILBOX_CPHS fail.
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (upto 10 characters)
     * @param voiceNumber dailing nubmer (upto 20 digits)
     *        if the number is start with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete) {
        log("setVoiceMailNumber, mIsVoiceMailFixed " + mIsVoiceMailFixed +
            ", mMailboxIndex " + mMailboxIndex + ", mMailboxIndex " + mMailboxIndex);
        if (mIsVoiceMailFixed) {
            AsyncResult.forMessage((onComplete)).exception =
                    new IccVmFixedException("Voicemail number is fixed by operator");
            onComplete.sendToTarget();
            return;
        }

        mNewVoiceMailNum = voiceNumber;
        mNewVoiceMailTag = alphaTag;

        AdnRecord adn = new AdnRecord(mNewVoiceMailTag, mNewVoiceMailNum);

        if (mMailboxIndex != 0 && mMailboxIndex != 0xff) {

            new AdnRecordLoader(mFh).updateEF(adn, EF_MBDN, EF_EXT6,
                    mMailboxIndex, null,
                    obtainMessage(EVENT_SET_MBDN_DONE, onComplete));

        } else if (isCphsMailboxEnabled()) {
            log("setVoiceMailNumber,load EF_MAILBOX_CPHS");
            new AdnRecordLoader(mFh).updateEF(adn, EF_MAILBOX_CPHS,
                    EF_EXT1, 1, null,
                    obtainMessage(EVENT_SET_CPHS_MAILBOX_DONE, onComplete));

        } else {
            log("setVoiceMailNumber,Update SIM voice mailbox error");
            AsyncResult.forMessage((onComplete)).exception =
                    new IccVmNotSupportedException("Update SIM voice mailbox error");
            onComplete.sendToTarget();
        }
    }

    @Override
    public String getVoiceMailAlphaTag()
    {
        return mVoiceMailTag;
    }

    /**
     * Sets the SIM voice message waiting indicator records
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     */
    @Override
    public void
    setVoiceMessageWaiting(int line, int countWaiting) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        try {
            if (mEfMWIS != null) {
                // TS 51.011 10.3.45

                // lsb of byte 0 is 'voicemail' status
                mEfMWIS[0] = (byte)((mEfMWIS[0] & 0xfe)
                                    | (countWaiting == 0 ? 0 : 1));

                // byte 1 is the number of voice messages waiting
                if (countWaiting < 0) {
                    // The spec does not define what this should be
                    // if we don't know the count
                    mEfMWIS[1] = 0;
                } else {
                    mEfMWIS[1] = (byte) countWaiting;
                }

                mFh.updateEFLinearFixed(
                    EF_MWIS, 1, mEfMWIS, null,
                    obtainMessage (EVENT_UPDATE_DONE, EF_MWIS, 0));
            }

            if (mEfCPHS_MWI != null) {
                    // Refer CPHS4_2.WW6 B4.2.3
                mEfCPHS_MWI[0] = (byte)((mEfCPHS_MWI[0] & 0xf0)
                            | (countWaiting == 0 ? 0x5 : 0xa));
                mFh.updateEFTransparent(
                    EF_VOICE_MAIL_INDICATOR_CPHS, mEfCPHS_MWI,
                    obtainMessage (EVENT_UPDATE_DONE, EF_VOICE_MAIL_INDICATOR_CPHS));
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            logw("Error saving voice mail state to SIM. Probably malformed SIM record", ex);
        }
    }

    // Validate data is !null and the MSP (Multiple Subscriber Profile)
    // byte is between 1 and 4. See ETSI TS 131 102 v11.3.0 section 4.2.64.
    private boolean validEfCfis(byte[] data) {
        return ((data != null) && (data[0] >= 1) && (data[0] <= 4));
    }

    public int getVoiceMessageCount() {
        boolean voiceMailWaiting = false;
        int countVoiceMessages = 0;
        if (mEfMWIS != null) {
            // Use this data if the EF[MWIS] exists and
            // has been loaded
            // Refer TS 51.011 Section 10.3.45 for the content description
            voiceMailWaiting = ((mEfMWIS[0] & 0x01) != 0);
            countVoiceMessages = mEfMWIS[1] & 0xff;

            if (voiceMailWaiting && countVoiceMessages == 0) {
                // Unknown count = -1
                countVoiceMessages = -1;
            }
            if(DBG) log(" VoiceMessageCount from SIM MWIS = " + countVoiceMessages);
        } else if (mEfCPHS_MWI != null) {
            // use voice mail count from CPHS
            int indicator = (int) (mEfCPHS_MWI[0] & 0xf);

            // Refer CPHS4_2.WW6 B4.2.3
            if (indicator == 0xA) {
                // Unknown count = -1
                countVoiceMessages = -1;
            } else if (indicator == 0x5) {
                countVoiceMessages = 0;
            }
            if(DBG) log(" VoiceMessageCount from SIM CPHS = " + countVoiceMessages);
        }
        return countVoiceMessages;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getVoiceCallForwardingFlag() {
        return mCallForwardingEnabled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setVoiceCallForwardingFlag(int line, boolean enable, String dialNumber) {

        if (line != 1) return; // only line 1 is supported

        mCallForwardingEnabled = enable;

        mRecordsEventsRegistrants.notifyResult(EVENT_CFI);

        // We don't update EF_CFU here because modem already done.
        /*
        try {
            if (validEfCfis(mEfCfis)) {
                // lsb is of byte 1 is voice status
                if (enable) {
                    mEfCfis[1] |= 1;
                } else {
                    mEfCfis[1] &= 0xfe;
                }

                log("setVoiceCallForwardingFlag: enable=" + enable
                        + " mEfCfis=" + IccUtils.bytesToHexString(mEfCfis));

                // Update dialNumber if not empty and CFU is enabled.
                // Spec reference for EF_CFIS contents, TS 51.011 section 10.3.46.
                if (enable && !TextUtils.isEmpty(dialNumber)) {
                    log("EF_CFIS: updating cf number, " + dialNumber);
                    byte[] bcdNumber = PhoneNumberUtils.numberToCalledPartyBCD(dialNumber);

                    System.arraycopy(bcdNumber, 0, mEfCfis, CFIS_TON_NPI_OFFSET, bcdNumber.length);

                    mEfCfis[CFIS_BCD_NUMBER_LENGTH_OFFSET] = (byte) (bcdNumber.length);
                    mEfCfis[CFIS_ADN_CAPABILITY_ID_OFFSET] = (byte) 0xFF;
                    mEfCfis[CFIS_ADN_EXTENSION_ID_OFFSET] = (byte) 0xFF;
                }

                mFh.updateEFLinearFixed(
                        EF_CFIS, 1, mEfCfis, null,
                        obtainMessage (EVENT_UPDATE_DONE, EF_CFIS));
            } else {
                log("setVoiceCallForwardingFlag: ignoring enable=" + enable
                        + " invalid mEfCfis=" + IccUtils.bytesToHexString(mEfCfis));
            }

            if (mEfCff != null) {
                if (enable) {
                    mEfCff[0] = (byte) ((mEfCff[0] & CFF_LINE1_RESET)
                            | CFF_UNCONDITIONAL_ACTIVE);
                } else {
                    mEfCff[0] = (byte) ((mEfCff[0] & CFF_LINE1_RESET)
                            | CFF_UNCONDITIONAL_DEACTIVE);
                }

                mFh.updateEFTransparent(
                        EF_CFF_CPHS, mEfCff,
                        obtainMessage (EVENT_UPDATE_DONE, EF_CFF_CPHS));
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            logw("Error saving call forwarding flag to SIM. "
                            + "Probably malformed SIM record", ex);

        }
        */
    }

    /**
     * Called by STK Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all SIM records that we cache.
            fetchSimRecords();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOperatorNumeric() {
        if (mImsi == null) {
            log("getOperatorNumeric: IMSI == null");
            return null;
        }
        if (mMncLength == UNINITIALIZED || mMncLength == UNKNOWN) {
            log("getSIMOperatorNumeric: bad mncLength");
            return null;
        }

        // Length = length of MCC + length of MNC
        // length of mcc = 3 (TS 23.003 Section 2.2)
        return mImsi.substring(0, 3 + mMncLength);
    }
	//add by zhaizhanfeng for virtual voicemail at 150811 start 
	public String getSIMOperatorNumeric() {
		if (mImsi == null || mMncLength == UNINITIALIZED || mMncLength == UNKNOWN) {
			return null;
		}
		// Length = length of MCC + length of MNC
		// length of mcc = 3 (TS 23.003 Section 2.2)
		return mImsi.substring(0, 3 + mMncLength);
		}
	//add by zhaizhanfeng for virtual voicemail at 150811 end

    @Override
    public String getSIMCPHSOns() {
        if (cphsOnsl != null) {
            return cphsOnsl;
        } else {
            return cphsOnss;
        }
    }

    // ***** Overridden from Handler
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        AdnRecord adn;

        byte data[];

        boolean isRecordLoadResponse = false;

        if (mDestroyed.get()) {
            loge("Received message " + msg + "[" + msg.what + "] " +
                    " while being destroyed. Ignoring.");
            return;
        }

        try { switch (msg.what) {
            case EVENT_APP_READY:
                onReady();
                fetchEccList();
                break;
            case EVENT_APP_LOCKED:
                onLocked();
                break;
            case EVENT_RADIO_STATE_CHANGED:
                if (DBG) log("handleMessage (EVENT_RADIO_STATE_CHANGED)");

                if (mCi.getRadioState().isAvailable()) {
                    fetchEccList();
                }
                break;

            /* IO events */
            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    loge("Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    loge("invalid IMSI " + mImsi);
                    mImsi = null;
                }

                log("IMSI: mMncLength=" + mMncLength);
                log("IMSI: " + mImsi.substring(0, 6) + "xxxxxxx");
                setSystemProperty(PROPERTY_ICC_OPERATOR_IMSI, mImsi);
				//modified by maolikui at 2015-09-19 start
				if (mMncLength != UNKNOWN && mMncLength != UNINITIALIZED && mMncLength > 3) {
					mMncLength = 2;
				}
				//modified by maolikui at 2015-09-19 end
                if (((mMncLength == UNINITIALIZED) || (mMncLength == UNKNOWN) || (mMncLength == 2)) &&
                        ((mImsi != null) && (mImsi.length() >= 6))) {
                    String mccmncCode = mImsi.substring(0, 6);
                    for (String mccmnc : MCCMNC_CODES_HAVING_3DIGITS_MNC) {
                        if (mccmnc.equals(mccmncCode)) {
                            mMncLength = 3;
                            log("IMSI: setting1 mMncLength=" + mMncLength);
                            break;
                        }
                    }
                }

                if (mMncLength == UNKNOWN || mMncLength == UNINITIALIZED) {
                    // the SIM has told us all it knows, but it didn't know the mnc length.
                    // guess using the mcc
                    try {
                        int mcc = Integer.parseInt(mImsi.substring(0,3));
                        mMncLength = MccTable.smallestDigitsMccForMnc(mcc);
                        log("setting2 mMncLength=" + mMncLength);
                    } catch (NumberFormatException e) {
                        mMncLength = UNKNOWN;
                        loge("Corrupt IMSI! setting3 mMncLength=" + mMncLength);
                    }
                }

                if (mMncLength != UNKNOWN && mMncLength != UNINITIALIZED) {
                    log("update mccmnc=" + mImsi.substring(0, 3 + mMncLength));
                    // finally have both the imsi and the mncLength and can parse the imsi properly
                    //MccTable.updateMccMncConfiguration(mContext,
                            //mImsi.substring(0, 3 + mMncLength), false);
                    if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                        && (SvlteUiccUtils.getInstance().isUsim(mSlotId))) {
                        log("updateConfiguration1 when ef_spn is loaded");
                    } else {
                        updateConfiguration(mImsi.substring(0, 3 + mMncLength));
                    }
                }
                if (!mImsi.equals(mSimImsi)) {
                    mSimImsi = mImsi;
                    mImsiReadyRegistrants.notifyRegistrants();
                    log("SimRecords: mImsiReadyRegistrants.notifyRegistrants");
                }
            break;

            case EVENT_GET_MBI_DONE:
                boolean isValidMbdn;
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[]) ar.result;

                isValidMbdn = false;
                if (ar.exception == null) {
                    // Refer TS 51.011 Section 10.3.44 for content details
                    log("EF_MBI: " + IccUtils.bytesToHexString(data));

                    // Voice mail record number stored first
                    mMailboxIndex = data[0] & 0xff;

                    // check if dailing numbe id valid
                    if (mMailboxIndex != 0 && mMailboxIndex != 0xff) {
                        log("Got valid mailbox number for MBDN");
                        isValidMbdn = true;
                        this.isValidMBI = true; // ALPS00301018
                    }
                }

                // one more record to load
                mRecordsToLoad += 1;

                if (isValidMbdn) {
                    // Note: MBDN was not included in NUM_OF_SIM_RECORDS_LOADED
                    log("EVENT_GET_MBI_DONE, to load EF_MBDN");
                    new AdnRecordLoader(mFh).loadFromEF(EF_MBDN, EF_EXT6,
                            mMailboxIndex, obtainMessage(EVENT_GET_MBDN_DONE));
                } else if (isCphsMailboxEnabled()) {
                    // If this EF not present, try mailbox as in CPHS standard
                    // CPHS (CPHS4_2.WW6) is a european standard.
                    log("EVENT_GET_MBI_DONE, to load EF_MAILBOX_CPHS");
                    new AdnRecordLoader(mFh).loadFromEF(EF_MAILBOX_CPHS,
                            EF_EXT1, 1,
                            obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                } else {
                    log("EVENT_GET_MBI_DONE, do nothing");
                    mRecordsToLoad -= 1;
                }

                break;
            case EVENT_GET_CPHS_MAILBOX_DONE:
            case EVENT_GET_MBDN_DONE:
                //Resetting the voice mail number and voice mail tag to null
                //as these should be updated from the data read from EF_MBDN.
                //If they are not reset, incase of invalid data/exception these
                //variables are retaining their previous values and are
                //causing invalid voice mailbox info display to user.
                mVoiceMailNum = null;
                mVoiceMailTag = null;
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {

                    log("Invalid or missing EF"
                        + ((msg.what == EVENT_GET_CPHS_MAILBOX_DONE) ? "[MAILBOX]" : "[MBDN]"));

                    // Bug #645770 fall back to CPHS
                    // FIXME should use SST to decide

                    if (msg.what == EVENT_GET_MBDN_DONE) {
                        //load CPHS on fail...
                        // FIXME right now, only load line1's CPHS voice mail entry

                        mRecordsToLoad += 1;
                        new AdnRecordLoader(mFh).loadFromEF(
                                EF_MAILBOX_CPHS, EF_EXT1, 1,
                                obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                    }
                    break;
                }

                adn = (AdnRecord)ar.result;

                log("VM: " + adn +
                        ((msg.what == EVENT_GET_CPHS_MAILBOX_DONE) ? " EF[MAILBOX]" : " EF[MBDN]"));

                if (adn.isEmpty() && msg.what == EVENT_GET_MBDN_DONE) {
                    // Bug #645770 fall back to CPHS
                    // FIXME should use SST to decide
                    // FIXME right now, only load line1's CPHS voice mail entry
                    mRecordsToLoad += 1;
                    new AdnRecordLoader(mFh).loadFromEF(
                            EF_MAILBOX_CPHS, EF_EXT1, 1,
                            obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));

                    break;
                }

                mVoiceMailNum = adn.getNumber();
                mVoiceMailTag = adn.getAlphaTag();
            break;

            case EVENT_GET_MSISDN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    log("Invalid or missing EF[MSISDN]");
                    break;
                }

                adn = (AdnRecord)ar.result;

                mMsisdn = adn.getNumber();
                mMsisdnTag = adn.getAlphaTag();

                mRecordsEventsRegistrants.notifyResult(EVENT_MSISDN);
                // / Commented out by guofeiyao for red line
                if (DBG) {
                    //log("MSISDN: " + mMsisdn);
                } else {
                    //log("MSISDN: " + /*mMsisdn*/ "xxxxxxx");
                }
                
            break;

            case EVENT_SET_MSISDN_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;

                if (ar.exception == null) {
                    mMsisdn = mNewMsisdn;
                    mMsisdnTag = mNewMsisdnTag;
                    mRecordsEventsRegistrants.notifyResult(EVENT_MSISDN);
                    log("Success to update EF[MSISDN]");
                }

                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;

            case EVENT_GET_MWIS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if(DBG) log("EF_MWIS : " + IccUtils.bytesToHexString(data));

                if (ar.exception != null) {
                    if(DBG) log("EVENT_GET_MWIS_DONE exception = "
                            + ar.exception);
                    break;
                }

                if ((data[0] & 0xff) == 0xff) {
                    if(DBG) log("SIMRecords: Uninitialized record MWIS");
                    break;
                }

                mEfMWIS = data;
                break;

            case EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if(DBG) log("EF_CPHS_MWI: " + IccUtils.bytesToHexString(data));

                if (ar.exception != null) {
                    if(DBG) log("EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE exception = "
                            + ar.exception);
                    break;
                }

                mEfCPHS_MWI = data;
                break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                mIccId = IccUtils.parseIccIdToString(data, 0, data.length);

                log("iccid: " + mIccId);

            break;


            case EVENT_GET_AD_DONE:
                try {
                    isRecordLoadResponse = true;

                    ar = (AsyncResult)msg.obj;
                    data = (byte[])ar.result;

                    if (ar.exception != null) {
                        break;
                    }

                    log("EF_AD: " + IccUtils.bytesToHexString(data));

                    if (data.length < 3) {
                        log("Corrupt AD data on SIM");
                        break;
                    }

                    if ((data[0] & 1) == 1 && (data[2] & 1) == 1) {
                        //TS31.102: EF_AD. If the bit1 of byte 1 is 1
                        //,then bit 1 of byte 3 is for ciphering.
                        log("SIMRecords: Cipher is enable");
                    }

                    if (data.length == 3) {
                        log("MNC length not present in EF_AD");
                        break;
                    }

                    mMncLength = data[3] & 0xf;
                    log("setting4 mMncLength=" + mMncLength);

                    if (mMncLength == 0xf) {
                        mMncLength = UNKNOWN;
                        log("setting5 mMncLength=" + mMncLength);
                    }
					//modified by maolikui at 2014-09-19 start	
					if (mMncLength != UNKNOWN && mMncLength != UNINITIALIZED && mMncLength > 3) {
						mMncLength = 2;
					}
					//modified by maolikui at 2014-09-19 end
                } finally {
                    if (((mMncLength == UNINITIALIZED) || (mMncLength == UNKNOWN) ||
                            (mMncLength == 2)) && ((mImsi != null) && (mImsi.length() >= 6))) {
                        String mccmncCode = mImsi.substring(0, 6);
                        log("mccmncCode=" + mccmncCode);
                        for (String mccmnc : MCCMNC_CODES_HAVING_3DIGITS_MNC) {
                            if (mccmnc.equals(mccmncCode)) {
                                mMncLength = 3;
                                log("setting6 mMncLength=" + mMncLength);
                                break;
                            }
                        }
                    }

                    if (mMncLength == UNKNOWN || mMncLength == UNINITIALIZED) {
                        if (mImsi != null) {
                            try {
                                int mcc = Integer.parseInt(mImsi.substring(0,3));

                                mMncLength = MccTable.smallestDigitsMccForMnc(mcc);
                                log("setting7 mMncLength=" + mMncLength);
                            } catch (NumberFormatException e) {
                                mMncLength = UNKNOWN;
                                loge("Corrupt IMSI! setting8 mMncLength=" + mMncLength);
                            }
                        } else {
                            // Indicate we got this info, but it didn't contain the length.
                            mMncLength = UNKNOWN;
                            log("MNC length not present in EF_AD setting9 mMncLength=" + mMncLength);
                        }
                    }
                    if (mImsi != null && mMncLength != UNKNOWN) {
                        // finally have both imsi and the length of the mnc and can parse
                        // the imsi properly
                        log("update mccmnc=" + mImsi.substring(0, 3 + mMncLength));
                        //MccTable.updateMccMncConfiguration(mContext,
                                //mImsi.substring(0, 3 + mMncLength), false);
                        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                            && (SvlteUiccUtils.getInstance().isUsim(mSlotId))) {
                            log("updateConfiguration2 when ef_spn is loaded");
                        } else {
                            updateConfiguration(mImsi.substring(0, 3 + mMncLength));
                        }
                    }
                }
            break;

            case EVENT_GET_SPN_DONE:
                if (DBG) log("EF_SPN loaded and try to extract: ");
                isRecordLoadResponse = true;
                ar = (AsyncResult) msg.obj;
                if (ar != null && ar.exception == null) {
                    if (DBG) log("getSpnFsm, Got data from EF_SPN");
                    data = (byte[]) ar.result;
                    mSpnDisplayCondition = 0xff & data[0];

                    // [ALPS00121176], 255 means invalid SPN file
                    if (mSpnDisplayCondition == 255) {
                        mSpnDisplayCondition = -1;
                    }

                    setServiceProviderName(IccUtils.adnStringFieldToString(data, 1, data.length - 1));
                    mSpNameInEfSpn = getServiceProviderName(); // MVNO-API
                    if (mSpNameInEfSpn != null && mSpNameInEfSpn.equals("")) {
                        if (DBG) log("set spNameInEfSpn to null because parsing result is empty");
                        mSpNameInEfSpn = null;
                    }

                    if (DBG) log("Load EF_SPN: " + getServiceProviderName()
                            + " spnDisplayCondition: " + mSpnDisplayCondition);
                    mTelephonyManager.setSimOperatorNameForPhone(mParentApp.getPhoneId(), getServiceProviderName());
                    if (mMncLength != UNKNOWN && mMncLength != UNINITIALIZED) {
                        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                            && (SvlteUiccUtils.getInstance().isUsim(mSlotId))) {
                            String spn = getServiceProviderName();
                            String operName = SpnOverride.getInstance().getSpnByEfSpn("20404", spn);
                            log("SPN loaded, spn=" + spn + "   operName = " + operName);
                            //It is for first power on to set language to chinese for ct card.
                            if ((spn != null) && (!spn.equals("")) && spn.equals(operName)) {
                                updateConfiguration("46011");
                                log("SPN loaded, update 46011 to set language");
                            } else {
                                updateConfiguration(mImsi.substring(0, 3 + mMncLength));
                                log("SPN loaded, update mccmnc ="
                                    + mImsi.substring(0, 3 + mMncLength));
                            }
                        }
                    }
                } else {
                    if (DBG) loge(": read spn fail!");
                    // See TS 51.011 10.3.11.  Basically, default to
                    // show PLMN always, and SPN also if roaming.
                    mSpnDisplayCondition = -1;

                    if (mMncLength != UNKNOWN && mMncLength != UNINITIALIZED) {
                        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                            && (SvlteUiccUtils.getInstance().isUsim(mSlotId))) {
                            updateConfiguration(mImsi.substring(0, 3 + mMncLength));
                            log("Read spn failed,Update mccmnc =" +
                                mImsi.substring(0, 3 + mMncLength));
                        }
                    }
                }
            break;

            case EVENT_GET_CFF_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception != null) {
                    break;
                }

                log("EF_CFF_CPHS: " + IccUtils.bytesToHexString(data));
                mEfCff = data;

                // if EF_CFIS is valid, prefer it to EF_CFF_CPHS
                if (!validEfCfis(mEfCfis) || !mCallForwardingEnabled) {
                    mCallForwardingEnabled =
                        ((data[0] & CFF_LINE1_MASK) == CFF_UNCONDITIONAL_ACTIVE);

                    mRecordsEventsRegistrants.notifyResult(EVENT_CFI);
                } else {
                    log("EVENT_GET_CFF_DONE: EF_CFIS is valid, ignoring EF_CFF_CPHS");
                }
                break;

            case EVENT_GET_SPDI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                parseEfSpdi(data);
            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    logw("update failed. ", ar.exception);
                }
            break;

            case EVENT_GET_PNN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                //data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                parseEFpnn((ArrayList) ar.result);
               /*
                SimTlv tlv = new SimTlv(data, 0, data.length);

                for ( ; tlv.isValidObject() ; tlv.nextObject()) {
                    if (tlv.getTag() == TAG_FULL_NETWORK_NAME) {
                        mPnnHomeName
                            = IccUtils.networkNameToString(
                                tlv.getData(), 0, tlv.getData().length);
                        break;
                    }
                }
                */
            break;

            case EVENT_GET_ALL_SMS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null)
                    break;

                handleSmses((ArrayList<byte []>) ar.result);
                break;

            case EVENT_MARK_SMS_READ_DONE:
                Rlog.i("ENF", "marked read: sms " + msg.arg1);
                break;


            case EVENT_SMS_ON_SIM:
                isRecordLoadResponse = false;

                ar = (AsyncResult)msg.obj;

                int[] index = (int[])ar.result;

                if (ar.exception != null || index.length != 1) {
                    loge("Error on SMS_ON_SIM with exp "
                            + ar.exception + " length " + index.length);
                } else {
                    log("READ EF_SMS RECORD index=" + index[0]);
                    mFh.loadEFLinearFixed(EF_SMS,index[0],
                            obtainMessage(EVENT_GET_SMS_DONE));
                }
                break;

            case EVENT_GET_SMS_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleSms((byte[])ar.result);
                } else {
                    loge("Error on GET_SMS with exp " + ar.exception);
                }
                break;
            case EVENT_GET_SST_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                mUsimServiceTable = new UsimServiceTable(data);
                if (DBG) log("SST: " + mUsimServiceTable);
                mEfSST = data;
                fetchMwisRecords();
                fetchPnnAndOpl();
                fetchSpn();
                fetchSmsp();
                fetchGbaRecords();
                break;

            case EVENT_GET_INFO_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                mCphsInfo = (byte[])ar.result;

                if (DBG) log("iCPHS: " + IccUtils.bytesToHexString(mCphsInfo));

                // ALPS00301018
                if (this.isValidMBI == false && isCphsMailboxEnabled()) {
                    mRecordsToLoad += 1;
                    new AdnRecordLoader(mFh).loadFromEF(EF_MAILBOX_CPHS,
                                EF_EXT1, 1,
                                obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                }
                break;

            case EVENT_SET_MBDN_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;

                if (DBG) log("EVENT_SET_MBDN_DONE ex:" + ar.exception);
                if (ar.exception == null) {
                    mVoiceMailNum = mNewVoiceMailNum;
                    mVoiceMailTag = mNewVoiceMailTag;
                }

                if (isCphsMailboxEnabled()) {
                    adn = new AdnRecord(mVoiceMailTag, mVoiceMailNum);
                    Message onCphsCompleted = (Message) ar.userObj;

                    /* write to cphs mailbox whenever it is available but
                    * we only need notify caller once if both updating are
                    * successful.
                    *
                    * so if set_mbdn successful, notify caller here and set
                    * onCphsCompleted to null
                    */
                    if (ar.exception == null && ar.userObj != null) {
                        AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = null;
                        ((Message) ar.userObj).sendToTarget();

                        if (DBG) log("Callback with MBDN successful.");

                        onCphsCompleted = null;
                    }

                    new AdnRecordLoader(mFh).
                            updateEF(adn, EF_MAILBOX_CPHS, EF_EXT1, 1, null,
                            obtainMessage(EVENT_SET_CPHS_MAILBOX_DONE,
                                    onCphsCompleted));
                } else {
                    if (ar.userObj != null) {
                        Resources resource = Resources.getSystem();
                        if (ar.exception != null && resource.getBoolean(com.android.internal.
                                    R.bool.editable_voicemailnumber)) {
                            // GSMPhone will store vm number on device
                            // when IccVmNotSupportedException occurred
                            AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = new IccVmNotSupportedException(
                                        "Update SIM voice mailbox error");
                        } else {
                            AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = ar.exception;
                        }
                        ((Message) ar.userObj).sendToTarget();
                    }
                }
                break;
            case EVENT_SET_CPHS_MAILBOX_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if(ar.exception == null) {
                    mVoiceMailNum = mNewVoiceMailNum;
                    mVoiceMailTag = mNewVoiceMailTag;
                } else {
                    if (DBG) log("Set CPHS MailBox with exception: "
                            + ar.exception);
                }
                if (ar.userObj != null) {
                    if (DBG) log("Callback with CPHS MB successful.");
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;
            case EVENT_SIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (DBG) log("Sim REFRESH with exception: " + ar.exception);
                if (ar.exception == null) {
                    handleSimRefresh((IccRefreshResponse)ar.result);
                }
                break;
            case EVENT_GET_CFIS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                log("EF_CFIS: " + IccUtils.bytesToHexString(data));

                if (validEfCfis(data)) {
                    mEfCfis = data;

                    // Refer TS 51.011 Section 10.3.46 for the content description
                    mCallForwardingEnabled = ((data[1] & 0x01) != 0);
                    log("EF_CFIS: callForwardingEnabled=" + mCallForwardingEnabled);

                    mRecordsEventsRegistrants.notifyResult(EVENT_CFI);
                } else {
                    log("EF_CFIS: invalid data=" + IccUtils.bytesToHexString(data));
                }
                break;

            case EVENT_GET_SIM_ECC_DONE:
                if (DBG) log("handleMessage (EVENT_GET_SIM_ECC_DONE)");

                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    if (DBG) loge("Get SIM ecc with exception: " + ar.exception);
                    break;
                }

                //ALPS00874200: Add 112 and 911 to ECC list.
                mEfEcc = "112,911";
                if (DBG) log("Reset mEfEcc to " + mEfEcc);

                data = (byte[]) ar.result;
                for (int i = 0 ; i + 2 < data.length ; i += 3) {
                    String eccNum;
                    eccNum = IccUtils.bcdToString(data, i, 3);
                    //MTK-START [mtk04070][120104][ALPS00109412]Solve "While making any outgoing call with international prefix "+", the no. is dialling emergency number"
                    //Merge from ALPS00102099
                    if (eccNum != null && !eccNum.equals("") && !mEfEcc.equals("")) {
                        mEfEcc = mEfEcc + ",";
                    }
                    //MTK-END [mtk04070][120104][ALPS00109412]Solve "While making any outgoing call with international prefix "+", the no. is dialling emergency number"
                    mEfEcc = mEfEcc + eccNum ;
                }

                if (DBG) log("SIM mEfEcc is " + mEfEcc);
                SystemProperties.set(SIM_RECORDS_PROPERTY_ECC_LIST[mSlotId], mEfEcc);
            break;

            case EVENT_GET_USIM_ECC_DONE:
                if (DBG) log("handleMessage (EVENT_GET_USIM_ECC_DONE)");

                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    if (DBG) loge("Get USIM ecc with exception: " + ar.exception);
                    break;
                }

                ArrayList eccRecords = (ArrayList) ar.result;
                int count = eccRecords.size();

                //ALPS00874200: Add 112 and 911 to ECC list.
                mEfEcc = "112,911";
                if (DBG) log("Reset mEfEcc to " + mEfEcc);

                for (int i = 0; i < count; i++) {
                    data = (byte[]) eccRecords.get(i);
                    if (DBG) log("USIM EF_ECC record " + count + ": " + IccUtils.bytesToHexString(data));
                    String eccNum;
                    eccNum = IccUtils.bcdToString(data, 0, 3);
                    if (eccNum != null && !eccNum.equals("")) {
                        if (!mEfEcc.equals("")) {
                            mEfEcc = mEfEcc + ",";
                        }
                        mEfEcc = mEfEcc + eccNum ;
                    }
                }

                if (DBG) log("USIM mEfEcc is " + mEfEcc);
                SystemProperties.set(SIM_RECORDS_PROPERTY_ECC_LIST[mSlotId], mEfEcc);
            break;

            case EVENT_GET_CSP_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    loge("Exception in fetching EF_CSP data " + ar.exception);
                    break;
                }

                data = (byte[])ar.result;

                log("EF_CSP: " + IccUtils.bytesToHexString(data));
                handleEfCspData(data);
                break;

            case EVENT_GET_GID1_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data =(byte[])ar.result;

                if (ar.exception != null) {
                    loge("Exception in get GID1 " + ar.exception);
                    mGid1 = null;
                    break;
                }
                mGid1 = IccUtils.bytesToHexString(data);
                log("GID1: " + mGid1);

                break;

            case EVENT_GET_ALL_OPL_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    break;
                }
                parseEFopl((ArrayList) ar.result);
                break;

            case EVENT_GET_CPHSONS_DONE:
                if (DBG) log("handleMessage (EVENT_GET_CPHSONS_DONE)");
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    cphsOnsl = IccUtils.adnStringFieldToString(
                            data, 0, data.length);

                    if (DBG) log("Load EF_SPN_CPHS: " + cphsOnsl);
                }
                break;

            case EVENT_GET_SHORT_CPHSONS_DONE:
                if (DBG) log("handleMessage (EVENT_GET_SHORT_CPHSONS_DONE)");
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    cphsOnss = IccUtils.adnStringFieldToString(
                            data, 0, data.length);

                    if (DBG) log("Load EF_SPN_SHORT_CPHS: " + cphsOnss);
                }
                break;

            case EVENT_PHB_READY:
                if (DBG) log("handleMessage (EVENT_PHB_READY)");

                ar = (AsyncResult) msg.obj;

                if (ar != null && ar.exception == null && ar.result != null) {

                    int[] isPhbReady = (int[]) ar.result;
                    String strAllSimState = "";
                    String strCurSimState = "";
                    boolean isSimLocked = false;
                    int phoneId = mParentApp.getPhoneId();

                    strAllSimState = SystemProperties.get(TelephonyProperties.PROPERTY_SIM_STATE);

                    if ((strAllSimState != null) && (strAllSimState.length() > 0)) {
                        String values[] = strAllSimState.split(",");
                        if ((phoneId >= 0) && (phoneId < values.length) && (values[phoneId] != null)) {
                            strCurSimState = values[phoneId];
                        }
                    }

                    isSimLocked = (strCurSimState.equals("NETWORK_LOCKED") || strCurSimState.equals("PIN_REQUIRED")); //if power on and in PUK_REQUIRED state, ap will not receive phb ready.

                    if (DBG) log("isPhbReady=" + isPhbReady[0] + ",strCurSimState = " + strCurSimState + ", isSimLocked = " + isSimLocked);

                    if (isPhbReady[0] > 0) {
                        if (false == isSimLocked) {
                            if (mPhbReady == false) {
                                mPhbReady = true;
                                //No need to update system property because it has been updated in rill.
                                broadcastPhbStateChangedIntent(mPhbReady);
                            }
                        } else {
                            log("phb ready but sim is not ready.");
                        }
                    } else {
                        if (mPhbReady == true) {
                            mAdnCache.reset();
                            mPhbReady = false;
                            //No need to update system property because it has been updated in rill.
                            broadcastPhbStateChangedIntent(mPhbReady);
                        }
                    }
                }

                break;
            case EVENT_MELOCK_CHANGED:
                if (DBG) log("handleMessage (EVENT_MELOCK_CHANGED)");
                ar = (AsyncResult) msg.obj;

                if (ar != null && ar.exception == null && ar.result != null) {
                    int[] simMelockEvent = (int []) ar.result;

                    if (DBG) log("sim melock event = " + simMelockEvent[0]);

                    RebootClickListener listener = new RebootClickListener();

                    if (simMelockEvent[0] == 0) {
                        AlertDialog alertDialog = new AlertDialog.Builder(mContext)
                            .setTitle("Unlock Phone")
                            .setMessage("Please restart the phone now since unlock setting has changed.")
                            .setPositiveButton("OK", listener)
                            .create();

                        alertDialog.setCancelable(false);
                        alertDialog.setCanceledOnTouchOutside(false);

                        alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                        alertDialog.show();
                    }
                }
                break;

            case EVENT_EF_CSP_PLMN_MODE_BIT_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar != null && ar.exception == null)  {
                    processEfCspPlmnModeBitUrc(((int[]) ar.result)[0]);
                }
                break;

            // ALPS00302702 RAT balancing
            case EVENT_GET_RAT_DONE:
                if (DBG) log("handleMessage (EVENT_GET_RAT_DONE)");
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                mEfRatLoaded = true;
                if (ar != null && ar.exception == null) {
                    mEfRat = ((byte[]) ar.result);
                    log("load EF_RAT complete: " + mEfRat[0]);
                    boradcastEfRatContentNotify(EF_RAT_FOR_OTHER_CASE);
                } else {
                    log("load EF_RAT fail");
                    mEfRat = null;
                    if (mParentApp.getType() == AppType.APPTYPE_USIM) {
                        boradcastEfRatContentNotify(EF_RAT_NOT_EXIST_IN_USIM);
                    } else {
                        boradcastEfRatContentNotify(EF_RAT_FOR_OTHER_CASE);
                    }
                }
                break;
//MTK-END [mtk80601][111215][ALPS00093395]

            /*
              Detail description:
              This feature provides a interface to get menu title string from EF_SUME
            */
            case EVENT_QUERY_MENU_TITLE_DONE:
                log("[sume receive response message");
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    if (data != null && data.length >= 2) {
                        int tag = data[0] & 0xff;
                        int len = data[1] & 0xff;
                        log("[sume tag = " + tag + ", len = " + len);
                        mMenuTitleFromEf = IccUtils.adnStringFieldToString(data, 2, len);
                        log("[sume menu title is " + mMenuTitleFromEf);
                    }
                } else {
                    if (ar.exception != null) {
                        log("[sume exception in AsyncResult: " + ar.exception.getClass().getName());
                    } else {
                        log("[sume null AsyncResult");
                    }
                    mMenuTitleFromEf = null;
                }

                break;
            case EVENT_RADIO_AVAILABLE:
                // TODO: Wait for isSetLanguageBySIM ready
                if (mTelephonyExt.isSetLanguageBySIM()) {
                    fetchLanguageIndicator();
                }
                mMsisdn = "";
                //setNumberToSimInfo();
                mRecordsEventsRegistrants.notifyResult(EVENT_MSISDN);
                break;
            case EVENT_GET_LI_DONE:
                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception == null) {
                   log("EF_LI: " +
                   IccUtils.bytesToHexString(data));
                   mEfLi = data;
                }
                onLanguageFileLoaded();
                break;
            case EVENT_GET_ELP_DONE:
                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception == null) {
                    log("EF_ELP: " +
                       IccUtils.bytesToHexString(data));
                    mEfELP = data;
                }
                onLanguageFileLoaded();
                break;

            case EVENT_GET_PSISMSC_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception != null) {
                    break;
                }

                log("EF_PSISMSC: " + IccUtils.bytesToHexString(data));

                if (data != null) {
                    mEfPsismsc = data;
                }
                break;

            case EVENT_GET_SMSP_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception != null) {
                    break;
                }

                log("EF_SMSP: " + IccUtils.bytesToHexString(data));

                if (data != null) {
                    mEfSmsp = data;
                }
                break;

            case EVENT_GET_GBABP_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                   data = ((byte[]) ar.result);
                   mGbabp = IccUtils.bytesToHexString(data);

                   if (DBG) log("EF_GBABP=" + mGbabp);
                } else {
                    loge("Error on GET_GBABP with exp " + ar.exception);
                }
                break;

            case EVENT_GET_GBANL_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mEfGbanlList = ((ArrayList<byte[]>) ar.result);
                    if (DBG) log("GET_GBANL record count: " + mEfGbanlList.size());
                } else {
                    loge("Error on GET_GBANL with exp " + ar.exception);
                }
                break;
            case EVENT_CFU_IND:
                ar = (AsyncResult) msg.obj;
                /* Line1 is enabled or disabled while reveiving this EVENT */
                if (ar != null && ar.exception == null && ar.result != null) {
                   /* Line1 is enabled or disabled while reveiving this EVENT */
                   int[] cfuResult = (int[]) ar.result;
                   log("handle EVENT_CFU_IND, setVoiceCallForwardingFlag:" + cfuResult[0]);
                   setVoiceCallForwardingFlag(1, (cfuResult[0] == 1), null);
                }
                break;

            case EVENT_IMSI_REFRESH_QUERY:
                log("handleMessage (EVENT_IMSI_REFRESH_QUERY) mImsi= " + mImsi);
                mCi.getIMSIForApp(mParentApp.getAid(),
                    obtainMessage(EVENT_IMSI_REFRESH_QUERY_DONE));
                break;
            case EVENT_IMSI_REFRESH_QUERY_DONE:
                log("handleMessage (EVENT_IMSI_REFRESH_QUERY_DONE)");
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    loge("Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;
                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    loge("invalid IMSI " + mImsi);
                    mImsi = null;
                }
                log("IMSI: mMncLength=" + mMncLength);
                log("IMSI: " + mImsi.substring(0, 6) + "xxxxxxx");
                setSystemProperty(PROPERTY_ICC_OPERATOR_IMSI, mImsi);

                if (((mMncLength == UNINITIALIZED) || (mMncLength == UNKNOWN) || (mMncLength == 2)) &&
                        ((mImsi != null) && (mImsi.length() >= 6))) {
                    String mccmncRefresh = mImsi.substring(0, 6);
                    for (String mccmncR: MCCMNC_CODES_HAVING_3DIGITS_MNC) {
                        if (mccmncR.equals(mccmncRefresh)) {
                            mMncLength = 3;
                            log("IMSI: setting1 mMncLength=" + mMncLength);
                            break;
                        }
                    }
                }
                if (mMncLength == UNKNOWN || mMncLength == UNINITIALIZED) {
                    // the SIM has told us all it knows, but it didn't know the mnc length.
                    // guess using the mcc
                    try {
                        int mccR = Integer.parseInt(mImsi.substring(0,3));
                        mMncLength = MccTable.smallestDigitsMccForMnc(mccR);
                        log("setting2 mMncLength=" + mMncLength);
                    } catch (NumberFormatException e) {
                        mMncLength = UNKNOWN;
                        loge("Corrupt IMSI! setting3 mMncLength=" + mMncLength);
                    }
                }
            
                if (mMncLength != UNKNOWN && mMncLength != UNINITIALIZED) {
                    log("update mccmnc=" + mImsi.substring(0, 3 + mMncLength));
                    // finally have both the imsi and the mncLength and can parse the imsi properly
                    //MccTable.updateMccMncConfiguration(mContext,
                            //mImsi.substring(0, 3 + mMncLength), false);
                    updateConfiguration(mImsi.substring(0, 3 + mMncLength));
                }
                if (!mImsi.equals(mSimImsi)) {
                    mSimImsi = mImsi;
                    mImsiReadyRegistrants.notifyRegistrants();
                    log("SimRecords: mImsiReadyRegistrants.notifyRegistrants");
                }

                if (mRecordsToLoad == 0 && mRecordsRequested == true) {
                    onAllRecordsLoaded();
                }
                break;
            // MTK-START
            /* Refine ICCID record updating by SYS PRO, 2015/03/23 { */
            case EVENT_GET_ICCID:
                final int delayMills = 1000;
                mIccId = SystemProperties.get(ICCRECORD_PROPERTY_ICCID[mSlotId], "");
                if (DBG) {
                    log("Received EVENT_GET_ICCID, mSlotId: " + mSlotId + ", mIccId: " + mIccId);
                }
                if (mIccId.equals("")) {
                    sendMessageDelayed(obtainMessage(EVENT_GET_ICCID, null), delayMills);
                } else {
                    if (mIccId.equals("N/A")) {
                        mIccId = "";
                    }
                    isRecordLoadResponse = true;
                }
                break;
            /* Refine ICCID record updating by SYS PRO, 2015/03/23 } */
            // MTK-END
            /** M: Bug Fix for ALPS02189616 */
            // handle new event
            case EVENT_DELAYED_SEND_PHB_CHANGE:
                boolean isReady = isPhbReady();
                log("[EVENT_DELAYED_SEND_PHB_CHANGE] isReady : " + isReady);
                broadcastPhbStateChangedIntent(isReady);
                break;

            default:
                super.handleMessage(msg);   // IccRecords handles generic record load responses

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            logw("Exception parsing SIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    private class EfPlLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_PL";
        }

        public void onRecordLoaded(AsyncResult ar) {
            mEfPl = (byte[]) ar.result;
            if (DBG) log("EF_PL=" + IccUtils.bytesToHexString(mEfPl));
        }
    }

    private class EfUsimLiLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_LI";
        }

        public void onRecordLoaded(AsyncResult ar) {
            mEfLi = (byte[]) ar.result;
            if (DBG) log("EF_LI=" + IccUtils.bytesToHexString(mEfLi));
        }
    }

    private void handleFileUpdate(int efid) {
        switch(efid) {
            case EF_MBDN:
                mRecordsToLoad++;
                new AdnRecordLoader(mFh).loadFromEF(EF_MBDN, EF_EXT6,
                        mMailboxIndex, obtainMessage(EVENT_GET_MBDN_DONE));
                break;
            case EF_MAILBOX_CPHS:
                mRecordsToLoad++;
                new AdnRecordLoader(mFh).loadFromEF(EF_MAILBOX_CPHS, EF_EXT1,
                        1, obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                break;
            case EF_CSP_CPHS:
                mRecordsToLoad++;
                log("[CSP] SIM Refresh for EF_CSP_CPHS");
                mFh.loadEFTransparent(EF_CSP_CPHS,
                        obtainMessage(EVENT_GET_CSP_CPHS_DONE));
                break;
            case EF_ADN:
            case EF_FDN:
            case EF_SDN:
            case EF_PBR:
                // ALPS00523253: If the file update is related to PHB efid, set phb ready to false
                if (false == mIsPhbEfResetDone) {
                    mIsPhbEfResetDone = true;
                    mAdnCache.reset();
                    log("handleFileUpdate ADN like");
                    setPhbReady(false);
                }
                break;
            default:
                // For now, fetch all records if this is not a
                // voicemail number.
                // TODO: Handle other cases, instead of fetching all.

                log("handleFileUpdate default");

                if (mAdnCache.isUsimPhbEfAndNeedReset(efid) == true) {
                    if (false == mIsPhbEfResetDone) {
                        mIsPhbEfResetDone = true;
                        mAdnCache.reset();
                        setPhbReady(false);
                    }
                }

                fetchSimRecords();
                break;
        }
    }

    private void handleSimRefresh(IccRefreshResponse refreshResponse){
        if (refreshResponse == null) {
            if (DBG) log("handleSimRefresh received without input");
            return;
        }

        if (refreshResponse.aid != null && !TextUtils.isEmpty(refreshResponse.aid) &&
                !refreshResponse.aid.equals(mParentApp.getAid())) {
            // This is for different app. Ignore.
            if (DBG) log("handleSimRefresh, refreshResponse.aid = " + refreshResponse.aid + ", mParentApp.getAid() = " + mParentApp.getAid());
            return;
        }

        switch (refreshResponse.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_FILE_UPDATE:
                if (DBG) log("handleSimRefresh with SIM_REFRESH_FILE_UPDATED");
                // result[1] contains the EFID of the updated file.
                //int efid = result[1];
                for (int i = 0; i < refreshResponse.efId.length; i++) {
                    handleFileUpdate(refreshResponse.efId[i]);
                }
                mIsPhbEfResetDone = false;
                break;
            case IccRefreshResponse.REFRESH_RESULT_INIT:
                if (DBG) log("handleSimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                setPhbReady(false);
                onIccRefreshInit();
                break;
            case IccRefreshResponse.REFRESH_RESULT_RESET:
                // Refresh reset is handled by the UiccCard object.
                if (DBG) log("handleSimRefresh with SIM_REFRESH_RESET");
                //if (requirePowerOffOnSimRefreshReset()) {
                    //mCi.setRadioPower(false, null);
                    /* Note: no need to call setRadioPower(true).  Assuming the desired
                    * radio power state is still ON (as tracked by ServiceStateTracker),
                    * ServiceStateTracker will call setRadioPower when it receives the
                    * RADIO_STATE_CHANGED notification for the power off.  And if the
                    * desired power state has changed in the interim, we don't want to
                    * override it with an unconditional power on.
                    */
                    TelephonyManager.MultiSimVariants mSimVar = TelephonyManager.getDefault().getMultiSimConfiguration();
                    log("mSimVar : " + mSimVar);
                    if (SystemProperties.get("ro.sim_refresh_reset_by_modem").equals("1") != true) {
                        log("sim_refresh_reset_by_modem false");
                        if (mSimVar != TelephonyManager.MultiSimVariants.DSDA/*!PhoneFactory.isDualTalkMode()*/) {
                            int phoneIdFor3G = SystemProperties.getInt(PROPERTY_3G_SIM, 1);
                            // [ALPS00432584][mtk02772]
                            // EPOF needs to sent to 3G Modem,
                            // if it already 3G switch, send EPOF directly
                            int phoneId = mPhone.getPhoneId();
                            log("phoneIdFor3G : " + phoneIdFor3G + " : " + phoneId);
                            if (phoneIdFor3G == phoneId/*mSlotId*/) {
                                mCi.resetRadio(null);
                            } else {
                                // notify phone 1 to reset modem
                                log("notify phone " + phoneIdFor3G + " to reset modem");
                                Intent intent = new Intent(ACTION_RESET_MODEM);
                                mContext.sendBroadcast(intent);
                            }
                        } else {
                            // reset modem directly
                            mCi.resetRadio(null);
                        }
                    } else {
                        log("Sim reset by modem!");
                    }
                //}
                mAdnCache.reset();
                setPhbReady(false);
                break;
            case IccRefreshResponse.REFRESH_INIT_FULL_FILE_UPDATED:
                //ALPS00848917: Add refresh type
                if (DBG) {
                    log("handleSimRefresh with REFRESH_INIT_FULL_FILE_UPDATED");
                }
                setPhbReady(false);
                onIccRefreshInit();
                break;
            case IccRefreshResponse.REFRESH_INIT_FILE_UPDATED:
                if (DBG) {
                    log("handleSimRefresh with REFRESH_INIT_FILE_UPDATED, EFID = " +  refreshResponse.efId);
                }
                for (int i = 0; i < refreshResponse.efId.length; i++) {
                    handleFileUpdate(refreshResponse.efId[i]);
                }
                mIsPhbEfResetDone = false;
                if (mParentApp.getState() == AppState.APPSTATE_READY) {
                    // This will cause files to be reread
                    sendMessage(obtainMessage(EVENT_APP_READY));
                }
                break;
            case IccRefreshResponse.REFRESH_SESSION_RESET:
                if (DBG) {
                    log("handleSimRefresh with REFRESH_SESSION_RESET");
                }
                // need to reload all files (that we care about)
                onIccRefreshInit();
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleSimRefresh with unknown operation");
                break;
        }
        // notify stk app to clear the idle text
        if (refreshResponse.refreshResult == IccRefreshResponse.REFRESH_RESULT_INIT ||
                refreshResponse.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET ||
                refreshResponse.refreshResult == IccRefreshResponse.REFRESH_INIT_FULL_FILE_UPDATED ||
                refreshResponse.refreshResult == IccRefreshResponse.REFRESH_INIT_FILE_UPDATED) {
            // impl
            log("notify stk app to remove the idle text");
            Intent intent;
            intent = new Intent(TelephonyIntents.ACTION_REMOVE_IDLE_TEXT);
            intent.putExtra(KEY_SIM_ID, mSlotId);
            mContext.sendBroadcast(intent);
        }

    }

    /**
     * Dispatch 3GPP format message to registrant ({@code GSMPhone} or {@code CDMALTEPhone})
     * to pass to the 3GPP SMS dispatcher for delivery.
     */
    private int dispatchGsmMessage(SmsMessage message) {
        mNewSmsRegistrants.notifyResult(message);
        return 0;
    }

    private void handleSms(byte[] ba) {
        if (ba[0] != 0)
            Rlog.d("ENF", "status : " + ba[0]);

        // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
        // 3 == "received by MS from network; message to be read"
        if (ba[0] == 3) {
            int n = ba.length;

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[n - 1];
            System.arraycopy(ba, 1, pdu, 0, n - 1);
            SmsMessage message = SmsMessage.createFromPdu(pdu, SmsConstants.FORMAT_3GPP);

            dispatchGsmMessage(message);
        }
    }


    private void handleSmses(ArrayList<byte[]> messages) {
        int count = messages.size();

        for (int i = 0; i < count; i++) {
            byte[] ba = messages.get(i);

            if (ba[0] != 0)
                Rlog.i("ENF", "status " + i + ": " + ba[0]);

            // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
            // 3 == "received by MS from network; message to be read"

            if (ba[0] == 3) {
                int n = ba.length;

                // Note: Data may include trailing FF's.  That's OK; message
                // should still parse correctly.
                byte[] pdu = new byte[n - 1];
                System.arraycopy(ba, 1, pdu, 0, n - 1);
                SmsMessage message = SmsMessage.createFromPdu(pdu, SmsConstants.FORMAT_3GPP);

                dispatchGsmMessage(message);

                // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
                // 1 == "received by MS from network; message read"

                ba[0] = 1;

                if (false) { // FIXME: writing seems to crash RdoServD
                    mFh.updateEFLinearFixed(EF_SMS,
                            i, ba, null, obtainMessage(EVENT_MARK_SMS_READ_DONE, i));
                }
            }
        }
    }

    private String findBestLanguage(byte[] languages) {
        String bestMatch = null;
        String[] locales = mContext.getAssets().getLocales();

        if ((languages == null) || (locales == null)) return null;

        // Each 2-bytes consists of one language
        for (int i = 0; (i + 1) < languages.length; i += 2) {
            try {
                String lang = new String(languages, i, 2, "ISO-8859-1");
                if (DBG) log ("languages from sim = " + lang);
                for (int j = 0; j < locales.length; j++) {
                    if (locales[j] != null && locales[j].length() >= 2 &&
                            locales[j].substring(0, 2).equalsIgnoreCase(lang)) {
                        return lang;
                    }
                }
                if (bestMatch != null) break;
            } catch(java.io.UnsupportedEncodingException e) {
                log ("Failed to parse USIM language records" + e);
            }
        }
        // no match found. return null
        return null;
    }

    private void setLocaleFromUsim() {
        String prefLang = null;
        // check EFli then EFpl
        prefLang = findBestLanguage(mEfLi);

        if (prefLang == null) {
            prefLang = findBestLanguage(mEfPl);
        }

        if (prefLang != null) {
            // check country code from SIM
            String imsi = getIMSI();
            String country = null;
            if (imsi != null) {
                country = MccTable.countryCodeForMcc(
                                    Integer.parseInt(imsi.substring(0,3)));
            }
            if (DBG) log("Setting locale to " + prefLang + "_" + country);
            MccTable.setSystemLocale(mContext, prefLang, country);
        } else {
            if (DBG) log ("No suitable USIM selected locale");
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        mRecordsToLoad -= 1;
        if (DBG) log("onRecordLoaded " + mRecordsToLoad + " requested: " + mRecordsRequested);

        if (mRecordsToLoad == 0 && mRecordsRequested == true) {
            onAllRecordsLoaded();
        } else if (mRecordsToLoad < 0) {
            loge("recordsToLoad <0, programmer error suspected");
            mRecordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
        if (DBG) log("record load complete");

        setLocaleFromUsim();

        if (mParentApp.getState() == AppState.APPSTATE_PIN ||
               mParentApp.getState() == AppState.APPSTATE_PUK ||
               mParentApp.getState() == AppState.APPSTATE_SUBSCRIPTION_PERSO) {
            // reset recordsRequested, since sim is not loaded really
            mRecordsRequested = false;
            // lock state, only update language
            return ;
        }

        // Some fields require more than one SIM record to set

        String operator = getOperatorNumeric();
        if (!TextUtils.isEmpty(operator)) {
            log("onAllRecordsLoaded set 'gsm.sim.operator.numeric' to operator='" +
                    operator + "'");
            log("update icc_operator_numeric=" + operator);
            mTelephonyManager.setSimOperatorNumericForPhone(
                    mParentApp.getPhoneId(), operator);
            final SubscriptionController subController = SubscriptionController.getInstance();
            subController.setMccMnc(operator, subController.getDefaultSmsSubId());
        } else {
            log("onAllRecordsLoaded empty 'gsm.sim.operator.numeric' skipping");
        }

        if (!TextUtils.isEmpty(mImsi)) {
            log("onAllRecordsLoaded set mcc imsi=" + mImsi);
            String countryCode;
            try {
                countryCode =
                    MccTable.countryCodeForMcc(Integer.parseInt(mImsi.substring(0, 3)));
            } catch (NumberFormatException e) {
                countryCode = null;
                loge("SIMRecords: Corrupt IMSI!");
            }
            mTelephonyManager.setSimCountryIsoForPhone(mParentApp.getPhoneId(), countryCode);
        } else {
            log("onAllRecordsLoaded empty imsi skipping setting mcc");
        }

        setVoiceMailByCountry(operator);
        setSpnFromConfig(operator);

        mRecordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));

        log("imsi = " + mImsi + " operator = " + operator);

        if (operator != null) {
            String newName = null;
            if (operator.equals("46002") || operator.equals("46007")) {
                operator = "46000";
            }
            newName = SpnOverride.getInstance().lookupOperatorName(
                    SubscriptionManager.getSubIdUsingPhoneId(mParentApp.getPhoneId()),
                    operator, true, mContext);
            //setOperatorForNewSIM(operator);

            setSystemProperty(PROPERTY_ICC_OPERATOR_DEFAULT_NAME, newName);

            // TODO: Subscription Manager have to receive intent or event to do what it want to do
            //ALPS00288486
            /*for Gemini phone, check the other SIM display name*/
            //boolean simLocaleProcessing = SystemProperties.getBoolean(TelephonyProperties.PROPERTY_SIM_LOCALE_SETTINGS, false);
            //if (simLocaleProcessing) {
                //We need to wait for Locale change.
            //    log("wait for setting locale done from the other card");
            //} else{
            //    setDefaultNameForNewSIM(newName);
            //}
        }
    }

    //***** Private methods

    private void setSpnFromConfig(String carrier) {
        // MTK-START
        // If EF_SPN has value, use it directly to avoid wrong spn-conf.xml to
        // override the EF_SPN value.
        if (TextUtils.isEmpty(getServiceProviderName()) && mSpnOverride.containsCarrier(carrier)) {
            //setServiceProviderName(mSpnOverride.getSpn(carrier));
            mTelephonyManager.setSimOperatorNameForPhone(
                    mParentApp.getPhoneId(), mSpnOverride.getSpn(carrier));
        }
        // MTK-END
    }


    private void setVoiceMailByCountry (String spn) {
		//add by zhaizhanfeng for virtual voicemail at 150811 start
	 if (SystemProperties.get("ro.hq.virtual.voice.mail").equals("1")){
		if (TextUtils.isEmpty(mVoiceMailTag) && TextUtils.isEmpty(mVoiceMailNum)) {
             String spnSIM = null;
             String operator = getSIMOperatorNumeric();
             if (operator != null) {
				 spnSIM = SpnOverride.getInstance().lookupOperatorName(
                    SubscriptionManager.getSubIdUsingPhoneId(mParentApp.getPhoneId()),
                    operator, true, mContext);
             }
             String imsi = (mImsi != null) ? mImsi : "";
             String gid1 = SystemProperties.get(SIM_RECORDS_PROPERTY_GID1_LIST[mSlotId]);
             String efsst = getFile();
             String[] data = mVmConfig.getVirtualVoiceMailData(spn, imsi, VoiceMailConstants.VOICEMIAL_BY_IMSI);
             if (data == null) {
                 data = mVmConfig.getVirtualVoiceMailData(spn, spnSIM, VoiceMailConstants.VOICEMIAL_BY_SPN);
             }
             if (data == null) {
                 data = mVmConfig.getVirtualVoiceMailData(spn, gid1, VoiceMailConstants.VOICEMIAL_BY_GID1);
             }
             if (data == null) {
                 data = mVmConfig.getVirtualVoiceMailData(spn, efsst, VoiceMailConstants.VOICEMIAL_BY_SST);
             }
             if (data != null) {
                 isSetByCountry = true;
                 mVoiceMailNum = data[VoiceMailConstants.NUMBER];
                 mVoiceMailTag = data[VoiceMailConstants.TAG];
                 return;
             }
        }
        if (TextUtils.isEmpty(mVoiceMailTag) && TextUtils.isEmpty(mVoiceMailNum) && mVmConfig.containsCarrier(spn)) {
            isSetByCountry = true;
		    mVoiceMailNum = mVmConfig.getVoiceMailNumber(spn);
            mVoiceMailTag = mVmConfig.getVoiceMailTag(spn);
        }
	 }else{ //add by zhaizhanfeng for virtual voicemail at 150811 end
	 	if (mVmConfig.containsCarrier(spn)) {
			log("setVoiceMailByCountry");
            mIsVoiceMailFixed = true;
            mVoiceMailNum = mVmConfig.getVoiceMailNumber(spn);
            mVoiceMailTag = mVmConfig.getVoiceMailTag(spn);
	 	}
	 }
    }

    @Override
    public void onReady() {
        fetchSimRecords();
    }

    private void onLocked() {
        if (DBG) log("only fetch EF_LI and EF_PL in lock state");
        loadEfLiAndEfPl();
    }

    private void loadEfLiAndEfPl() {
        Resources resource = Resources.getSystem();
        if (!resource.getBoolean(com.android.internal.R.bool.config_use_sim_language_file)) {
            if (DBG) log ("Not using EF LI/EF PL");
            return;
        }

        if (mParentApp.getType() == AppType.APPTYPE_USIM) {
            mRecordsRequested = true;
            mFh.loadEFTransparent(EF_LI,
                    obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfUsimLiLoaded()));
            mRecordsToLoad++;

            mFh.loadEFTransparent(EF_PL,
                    obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfPlLoaded()));
            mRecordsToLoad++;
        }
    }

    protected void fetchSimRecords() {
        mRecordsRequested = true;

        if (DBG) log("fetchSimRecords " + mRecordsToLoad);

        mCi.getIMSIForApp(mParentApp.getAid(), obtainMessage(EVENT_GET_IMSI_DONE));
        mRecordsToLoad++;

        //iccFh.loadEFTransparent(EF_ICCID, obtainMessage(EVENT_GET_ICCID_DONE));
        //mRecordsToLoad++;
        // MTK-START
        /* Refine ICCID record updating by SYS PRO, 2015/03/23 {*/
        getIccIdRecord();
        mRecordsToLoad++;
        /* Refine ICCID record updating by SYS PRO, 2015/03/23 }*/
        // MTK-END

        // FIXME should examine EF[MSISDN]'s capability configuration
        // to determine which is the voice/data/fax line
        new AdnRecordLoader(mFh).loadFromEF(EF_MSISDN, EF_EXT1, 1,
                    obtainMessage(EVENT_GET_MSISDN_DONE));
        mRecordsToLoad++;

        // Record number is subscriber profile
        mFh.loadEFLinearFixed(EF_MBI, 1, obtainMessage(EVENT_GET_MBI_DONE));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_AD, obtainMessage(EVENT_GET_AD_DONE));
        mRecordsToLoad++;

        // Record number is subscriber profile
        // MTK-START
        // [ALPS01888298] should check service before access MWIS EF
        //mFh.loadEFLinearFixed(EF_MWIS, 1, obtainMessage(EVENT_GET_MWIS_DONE));
        //mRecordsToLoad++;
        // MTK-END

        // Also load CPHS-style voice mail indicator, which stores
        // the same info as EF[MWIS]. If both exist, both are updated
        // but the EF[MWIS] data is preferred
        // Please note this must be loaded after EF[MWIS]
        mFh.loadEFTransparent(
                EF_VOICE_MAIL_INDICATOR_CPHS,
                obtainMessage(EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE));
        mRecordsToLoad++;

        // Same goes for Call Forward Status indicator: fetch both
        // EF[CFIS] and CPHS-EF, with EF[CFIS] preferred.
        mFh.loadEFLinearFixed(EF_CFIS, 1, obtainMessage(EVENT_GET_CFIS_DONE));
        mRecordsToLoad++;
        mFh.loadEFTransparent(EF_CFF_CPHS, obtainMessage(EVENT_GET_CFF_DONE));
        mRecordsToLoad++;


        //getSpnFsm(true, null);

        mFh.loadEFTransparent(EF_SPDI, obtainMessage(EVENT_GET_SPDI_DONE));
        mRecordsToLoad++;

        //mFh.loadEFLinearFixed(EF_PNN, 1, obtainMessage(EVENT_GET_PNN_DONE));
        //recordsToLoad++;

        mFh.loadEFTransparent(EF_SST, obtainMessage(EVENT_GET_SST_DONE));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_INFO_CPHS, obtainMessage(EVENT_GET_INFO_CPHS_DONE));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_CSP_CPHS,obtainMessage(EVENT_GET_CSP_CPHS_DONE));
        mRecordsToLoad++;

        mFh.loadEFTransparent(EF_GID1, obtainMessage(EVENT_GET_GID1_DONE));
        mRecordsToLoad++;

        loadEfLiAndEfPl();

        // TODO: Wait for isSetLanguageBySIM ready
        /*
                Detail description:
                This feature provides a interface to get menu title string from EF_SUME
                */
        if (mTelephonyExt != null) {
            if (mTelephonyExt.isSetLanguageBySIM()) {
                mFh.loadEFTransparent(EF_SUME, obtainMessage(EVENT_QUERY_MENU_TITLE_DONE));
                mRecordsToLoad++;
            }
        } else {
           loge("fetchSimRecords(): mTelephonyExt is null!!!");
        }

        fetchCPHSOns();


        // XXX should seek instead of examining them all
        if (false) { // XXX
            mFh.loadEFLinearFixedAll(EF_SMS, obtainMessage(EVENT_GET_ALL_SMS_DONE));
            mRecordsToLoad++;
        }

        if (CRASH_RIL) {
            String sms = "0107912160130310f20404d0110041007030208054832b0120"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         + "ffffffffffffffffffffffffffffff";
            byte[] ba = IccUtils.hexStringToBytes(sms);

            mFh.updateEFLinearFixed(EF_SMS, 1, ba, null,
                            obtainMessage(EVENT_MARK_SMS_READ_DONE, 1));
        }
        if (DBG) log("fetchSimRecords " + mRecordsToLoad + " requested: " + mRecordsRequested);
        fetchRatBalancing();
    }

    /**
     * Returns the SpnDisplayRule based on settings on the SIM and the
     * specified plmn (currently-registered PLMN).  See TS 22.101 Annex A
     * and TS 51.011 10.3.11 for details.
     *
     * If the SPN is not found on the SIM or is empty, the rule is
     * always PLMN_ONLY.
     */
    @Override
    public int getDisplayRule(String plmn) {
        int rule;
        boolean bSpnActive = false;
        String spn = getServiceProviderName();

        if (mEfSST != null && mParentApp != null) {
            if (mParentApp.getType() == AppType.APPTYPE_USIM) {
                if (mEfSST.length >= 3 && (mEfSST[2] & 0x04) == 4) {
                    bSpnActive = true;
                    log("getDisplayRule USIM mEfSST is " +
                     IccUtils.bytesToHexString(mEfSST) + " set bSpnActive to true");
                }
            } else if ((mEfSST.length >= 5) && (mEfSST[4] & 0x02) == 2) {
                bSpnActive = true;
                log("getDisplayRule SIM mEfSST is " +
                    IccUtils.bytesToHexString(mEfSST) + " set bSpnActive to true");
            }
        }

        log("getDisplayRule mParentApp is " +
            ((mParentApp != null) ? mParentApp : "null"));
        if (mParentApp != null && mParentApp.getUiccCard() != null &&
              mParentApp.getUiccCard().getOperatorBrandOverride() != null) {
            // If the operator has been overridden, treat it as the SPN file on the SIM did not exist.
            log("getDisplayRule, getOperatorBrandOverride is not null");
            rule = SPN_RULE_SHOW_PLMN;
        } else if (!bSpnActive || TextUtils.isEmpty(spn) || spn.equals("") || mSpnDisplayCondition == -1) {
            // No EF_SPN content was found on the SIM, or not yet loaded.  Just show ONS.
            log("getDisplayRule, no EF_SPN");
            rule = SPN_RULE_SHOW_PLMN;
        } else if (isOnMatchingPlmn(plmn)) {
            rule = SPN_RULE_SHOW_SPN;
            if ((mSpnDisplayCondition & 0x01) == 0x01) {
                // ONS required when registered to HPLMN or PLMN in EF_SPDI
                rule |= SPN_RULE_SHOW_PLMN;
            }
        } else {
            rule = SPN_RULE_SHOW_PLMN;
            if ((mSpnDisplayCondition & 0x02) == 0x00) {
                // SPN required if not registered to HPLMN or PLMN in EF_SPDI
                rule |= SPN_RULE_SHOW_SPN;
            }
        }
        return rule;
    }

    /**
     * Checks if plmn is HPLMN or on the spdiNetworks list.
     */
    private boolean isOnMatchingPlmn(String plmn) {
        if (plmn == null) return false;

        if (isHPlmn(plmn)) {
            return true;
        }

        if (mSpdiNetworks != null) {
            for (String spdiNet : mSpdiNetworks) {
                if (plmn.equals(spdiNet)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * States of Get SPN Finite State Machine which only used by getSpnFsm()
     */
    private enum GetSpnFsmState {
        IDLE,               // No initialized
        INIT,               // Start FSM
        READ_SPN_3GPP,      // Load EF_SPN firstly
        READ_SPN_CPHS,      // Load EF_SPN_CPHS secondly
        READ_SPN_SHORT_CPHS // Load EF_SPN_SHORT_CPHS last
    }

    /**
     * Finite State Machine to load Service Provider Name , which can be stored
     * in either EF_SPN (3GPP), EF_SPN_CPHS, or EF_SPN_SHORT_CPHS (CPHS4.2)
     *
     * After starting, FSM will search SPN EFs in order and stop after finding
     * the first valid SPN
     *
     * If the FSM gets restart while waiting for one of
     * SPN EFs results (i.e. a SIM refresh occurs after issuing
     * read EF_CPHS_SPN), it will re-initialize only after
     * receiving and discarding the unfinished SPN EF result.
     *
     * @param start set true only for initialize loading
     * @param ar the AsyncResult from loadEFTransparent
     *        ar.exception holds exception in error
     *        ar.result is byte[] for data in success
     */
    private void getSpnFsm(boolean start, AsyncResult ar) {
        byte[] data;

        if (start) {
            // Check previous state to see if there is outstanding
            // SPN read
            if(mSpnState == GetSpnFsmState.READ_SPN_3GPP ||
               mSpnState == GetSpnFsmState.READ_SPN_CPHS ||
               mSpnState == GetSpnFsmState.READ_SPN_SHORT_CPHS ||
               mSpnState == GetSpnFsmState.INIT) {
                // Set INIT then return so the INIT code
                // will run when the outstanding read done.
                mSpnState = GetSpnFsmState.INIT;
                return;
            } else {
                mSpnState = GetSpnFsmState.INIT;
            }
        }

        switch(mSpnState){
            case INIT:
                mFh.loadEFTransparent(EF_SPN,
                        obtainMessage(EVENT_GET_SPN_DONE));
                mRecordsToLoad++;

                mSpnState = GetSpnFsmState.READ_SPN_3GPP;
                break;
            case READ_SPN_3GPP:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    mSpnDisplayCondition = 0xff & data[0];
                    setServiceProviderName(IccUtils.adnStringFieldToString(
                            data, 1, data.length - 1));

                    if (DBG) log("Load EF_SPN: " + getServiceProviderName()
                            + " spnDisplayCondition: " + mSpnDisplayCondition);
                    mTelephonyManager.setSimOperatorNameForPhone(
                            mParentApp.getPhoneId(), getServiceProviderName());

                    mSpnState = GetSpnFsmState.IDLE;
                } else {
                    mFh.loadEFTransparent( EF_SPN_CPHS,
                            obtainMessage(EVENT_GET_SPN_DONE));
                    mRecordsToLoad++;

                    mSpnState = GetSpnFsmState.READ_SPN_CPHS;

                    // See TS 51.011 10.3.11.  Basically, default to
                    // show PLMN always, and SPN also if roaming.
                    mSpnDisplayCondition = -1;
                }
                break;
            case READ_SPN_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    setServiceProviderName(IccUtils.adnStringFieldToString(data, 0, data.length));

                    if (DBG) log("Load EF_SPN_CPHS: " + getServiceProviderName());
                    mTelephonyManager.setSimOperatorNameForPhone(
                            mParentApp.getPhoneId(), getServiceProviderName());

                    mSpnState = GetSpnFsmState.IDLE;
                } else {
                    mFh.loadEFTransparent(
                            EF_SPN_SHORT_CPHS, obtainMessage(EVENT_GET_SPN_DONE));
                    mRecordsToLoad++;

                    mSpnState = GetSpnFsmState.READ_SPN_SHORT_CPHS;
                }
                break;
            case READ_SPN_SHORT_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    setServiceProviderName(IccUtils.adnStringFieldToString(data, 0, data.length));

                    if (DBG) log("Load EF_SPN_SHORT_CPHS: " + getServiceProviderName());
                    mTelephonyManager.setSimOperatorNameForPhone(
                            mParentApp.getPhoneId(), getServiceProviderName());
                }else {
                    if (DBG) log("No SPN loaded in either CHPS or 3GPP");
                }

                mSpnState = GetSpnFsmState.IDLE;
                break;
            default:
                mSpnState = GetSpnFsmState.IDLE;
        }
    }

    /**
     * Parse TS 51.011 EF[SPDI] record
     * This record contains the list of numeric network IDs that
     * are treated specially when determining SPN display
     */
    private void
    parseEfSpdi(byte[] data) {
        SimTlv tlv = new SimTlv(data, 0, data.length);

        byte[] plmnEntries = null;

        for ( ; tlv.isValidObject() ; tlv.nextObject()) {
            // Skip SPDI tag, if existant
            if (tlv.getTag() == TAG_SPDI) {
              tlv = new SimTlv(tlv.getData(), 0, tlv.getData().length);
            }
            // There should only be one TAG_SPDI_PLMN_LIST
            if (tlv.getTag() == TAG_SPDI_PLMN_LIST) {
                plmnEntries = tlv.getData();
                break;
            }
        }

        if (plmnEntries == null) {
            return;
        }

        mSpdiNetworks = new ArrayList<String>(plmnEntries.length / 3);

        for (int i = 0 ; i + 2 < plmnEntries.length ; i += 3) {
            String plmnCode;
            plmnCode = IccUtils.parsePlmnToString(plmnEntries, i, 3);

            // Valid operator codes are 5 or 6 digits
            if (plmnCode.length() >= 5) {
                log("EF_SPDI network: " + plmnCode);
                mSpdiNetworks.add(plmnCode);
            }
        }
    }

    /**
     * check to see if Mailbox Number is allocated and activated in CPHS SST
     */
    private boolean isCphsMailboxEnabled() {
        if (mCphsInfo == null)  return false;
        return ((mCphsInfo[1] & CPHS_SST_MBN_MASK) == CPHS_SST_MBN_ENABLED );
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[SIMRecords] " + s + " (slot " + mSlotId + ")");
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[SIMRecords] " + s + " (slot " + mSlotId + ")");
    }

    protected void logw(String s, Throwable tr) {
        Rlog.w(LOG_TAG, "[SIMRecords] " + s + " (slot " + mSlotId + ")", tr);
    }

    protected void logv(String s) {
        Rlog.v(LOG_TAG, "[SIMRecords] " + s + " (slot " + mSlotId + ")");
    }

    /**
     * Return true if "Restriction of menu options for manual PLMN selection"
     * bit is set or EF_CSP data is unavailable, return false otherwise.
     */
    @Override
    public boolean isCspPlmnEnabled() {
        return mCspPlmnEnabled;
    }

    /**
     * Parse EF_CSP data and check if
     * "Restriction of menu options for manual PLMN selection" is
     * Enabled/Disabled
     *
     * @param data EF_CSP hex data.
     */
    private void handleEfCspData(byte[] data) {
        // As per spec CPHS4_2.WW6, CPHS B.4.7.1, EF_CSP contains CPHS defined
        // 18 bytes (i.e 9 service groups info) and additional data specific to
        // operator. The valueAddedServicesGroup is not part of standard
        // services. This is operator specific and can be programmed any where.
        // Normally this is programmed as 10th service after the standard
        // services.
        int usedCspGroups = data.length / 2;
        // This is the "Service Group Number" of "Value Added Services Group".
        byte valueAddedServicesGroup = (byte)0xC0;

        mCspPlmnEnabled = true;
        for (int i = 0; i < usedCspGroups; i++) {
             if (data[2 * i] == valueAddedServicesGroup) {
                 log("[CSP] found ValueAddedServicesGroup, value " + data[(2 * i) + 1]);
                 if ((data[(2 * i) + 1] & 0x80) == 0x80) {
                     // Bit 8 is for
                     // "Restriction of menu options for manual PLMN selection".
                     // Operator Selection menu should be enabled.
                     mCspPlmnEnabled = true;
                 } else {
                     mCspPlmnEnabled = false;
                     // Operator Selection menu should be disabled.
                     // Operator Selection Mode should be set to Automatic.
                     log("[CSP] Set Automatic Network Selection");
                     mNetworkSelectionModeAutomaticRegistrants.notifyRegistrants();
                 }
                 return;
             }
        }

        log("[CSP] Value Added Service Group (0xC0), not found!");
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (android.os.Build.TYPE.equals("eng")) {
            pw.println("SIMRecords: " + this);
        }
        pw.println(" extends:");
        super.dump(fd, pw, args);
        pw.println(" mVmConfig=" + mVmConfig);
        pw.println(" mSpnOverride=" + mSpnOverride);
        pw.println(" mCallForwardingEnabled=" + mCallForwardingEnabled);
        pw.println(" mSpnState=" + mSpnState);
        pw.println(" mCphsInfo=" + mCphsInfo);
        pw.println(" mCspPlmnEnabled=" + mCspPlmnEnabled);
        pw.println(" mEfMWIS[]=" + Arrays.toString(mEfMWIS));
        pw.println(" mEfCPHS_MWI[]=" + Arrays.toString(mEfCPHS_MWI));
        pw.println(" mEfCff[]=" + Arrays.toString(mEfCff));
        pw.println(" mEfCfis[]=" + Arrays.toString(mEfCfis));
        pw.println(" mSpnDisplayCondition=" + mSpnDisplayCondition);
        pw.println(" mSpdiNetworks[]=" + mSpdiNetworks);
        pw.println(" mPnnHomeName=" + mPnnHomeName);
        pw.println(" mUsimServiceTable=" + mUsimServiceTable);
        pw.println(" mGid1=" + mGid1);
        pw.flush();
    }

    // Added by M begin
    // MVNO-API START
    public String getSpNameInEfSpn() {
        if (DBG) log("getSpNameInEfSpn(): " + mSpNameInEfSpn);
        return mSpNameInEfSpn;
    }

    public String isOperatorMvnoForImsi() {
        SpnOverride spnOverride = SpnOverride.getInstance();
        String imsiPattern = spnOverride.isOperatorMvnoForImsi(getOperatorNumeric(),
                getIMSI());
        String mccmnc = getOperatorNumeric();
        if (DBG) {
            log("isOperatorMvnoForImsi(), imsiPattern: " + imsiPattern
                + ", mccmnc: " + mccmnc);
        }
        if (imsiPattern == null || mccmnc == null) {
            return null;
        }
        String result = imsiPattern.substring(mccmnc.length(), imsiPattern.length());
        if (DBG) {
            log("isOperatorMvnoForImsi(): " + result);
        }
        return result;
    }


    public String getFirstFullNameInEfPnn() {
        if (mPnnNetworkNames == null || mPnnNetworkNames.size() == 0) {
            if (DBG) log("getFirstFullNameInEfPnn(): empty");
            return null;
        }

        OperatorName opName = mPnnNetworkNames.get(0);
        if (DBG) log("getFirstFullNameInEfPnn(): first fullname: " + opName.sFullName);
        if (opName.sFullName != null)
            return new String(opName.sFullName);
        return null;
    }

    public String isOperatorMvnoForEfPnn() {
        String MCCMNC = getOperatorNumeric();
        String PNN = getFirstFullNameInEfPnn();
        if (DBG) log("isOperatorMvnoForEfPnn(): mccmnc = " + MCCMNC + ", pnn = " + PNN);
        if (SpnOverride.getInstance().getSpnByEfPnn(MCCMNC, PNN) != null)
            return PNN;
        return null;
    }

    public String getMvnoMatchType() {
        String IMSI = getIMSI();
        String SPN = getSpNameInEfSpn();
        String PNN = getFirstFullNameInEfPnn();
        String GID1 = getGid1();
		String FILE = getFile();//add by zhaizhanfeng for virtual voicemail at 150811
        String MCCMNC = getOperatorNumeric();
        if (DBG) log("getMvnoMatchType(): imsi = " + IMSI + ", mccmnc = " + MCCMNC + ", spn = " + SPN);

        if (SpnOverride.getInstance().getSpnByEfSpn(MCCMNC, SPN) != null)
            return PhoneConstants.MVNO_TYPE_SPN;

        if (SpnOverride.getInstance().getSpnByImsi(MCCMNC, IMSI) != null)
            return PhoneConstants.MVNO_TYPE_IMSI;

        if (SpnOverride.getInstance().getSpnByEfPnn(MCCMNC, PNN) != null)
            return PhoneConstants.MVNO_TYPE_PNN;

        if (SpnOverride.getInstance().getSpnByEfGid1(MCCMNC, GID1) != null)
            return PhoneConstants.MVNO_TYPE_GID;
        return PhoneConstants.MVNO_TYPE_NONE;
    }
    // MVNO-API END

    private class SIMBroadCastReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals("com.mediatek.dm.LAWMO_WIPE")) {
               wipeAllSIMContacts();
            } else {
                if (action.equals("action_pin_dismiss")) {
                    int simId = intent.getIntExtra("simslot", PhoneConstants.SIM_ID_1);
                    if (simId == mSlotId) {
                        log("SIMRecords receive pin dismiss intent for slot " + simId);
                        //setDefaultNameForNewSIM(null);
                   }
                } else if (action.equals("action_melock_dismiss")) {
                   int simId = intent.getIntExtra("simslot", /*PhoneConstants.SIM_ID_1*/0);
                   if (simId == mSlotId) {
                       log("SIMRecords receive SIM ME lock dismiss intent for slot " + simId);
                       //setDefaultNameForNewSIM(null);
                   }
                } else if (action.equals("android.intent.action.ACTION_SHUTDOWN_IPO")) {
                   processShutdownIPO();
                   // ALPS00293301
                   //SystemProperties.set(PROPERTY_ICC_OPERATOR_DEFAULT_NAME, null);
                   //if(FeatureOption.MTK_GEMINI_SUPPORT)
                   //    SystemProperties.set(PROPERTY_ICC_OPERATOR_DEFAULT_NAME_2, null);

                   //ALPS01213113
                   SystemProperties.set(SIM_RECORDS_PROPERTY_ECC_LIST[mSlotId], null);

                   // ALPS00302698 ENS
                   log("wipeAllSIMContacts ACTION_SHUTDOWN_IPO: reset mCspPlmnEnabled");
                   mCspPlmnEnabled = true;

                   // TODO: Wait for isSetLanguageBySIM ready
                   // ALPS00302702 RAT balancing
                   if (mTelephonyExt.isSetLanguageBySIM()) {
                       mEfRatLoaded = false;
                       mEfRat = null;
                   }

                   mAdnCache.reset();
                   log("wipeAllSIMContacts ACTION_SHUTDOWN_IPO");
                } /* else if(action.equals(Intent.ACTION_LOCALE_CHANGED)) { //ALPS00288486
                   log("SIMBroadCastReceiver action = " + action);
                   SystemProperties.set(TelephonyProperties.PROPERTY_SIM_LOCALE_SETTINGS, "false");
                   //ALPS00810356: Change SIM display name dynamically with system language.
                   setDefaultNameByLocale();
                } */ else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                    String reasonExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                    int slot = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                    log("SIM_STATE_CHANGED: slot = " + slot + ",reason = " + reasonExtra);
                    if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(reasonExtra)) {
                        if (slot == mSlotId) {
                            String strPuk1Count = null;
                            strPuk1Count = SystemProperties.get(SIMRECORD_PROPERTY_RIL_PUK1[mSlotId], "0");
                            log("SIM_STATE_CHANGED: strPuk1Count = " + strPuk1Count);
                            //if (strPuk1Count.equals("0")){
                            //    setPhbReady(false);
                            //}

                            mMsisdn = "";
                            //setNumberToSimInfo();
                            mRecordsEventsRegistrants.notifyResult(EVENT_MSISDN);
                        }
                    }
                }
            }
        }
    }

    private class SubBroadCastReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if ((mPhbWaitSub == true) && (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED))) {
                log("SubBroadCastReceiver receive ACTION_SUBINFO_RECORD_UPDATED");
                mPhbWaitSub = false;
                //modified by maolikui at 2015-09-19 start
 		//broadcastPhbStateChangedIntent(mPhbReady);
	        broadcastPhbStateChangedIntent(isPhbReady());
                //modified by maolikui at 2015-09-19 end 
            }
        }
    }

    private void wipeAllSIMContacts() {
        if (DBG) log("wipeAllSIMContacts");
        mAdnCache.reset();
        if (DBG) log("wipeAllSIMContacts after reset");
    }

    private void processShutdownIPO() {
        // reset icc id variable when ipo shutdown
        // ipo shutdown will make radio turn off,
        // only needs to reset the variable which will not be reset in onRadioOffOrNotAvailable()
        hasQueryIccId = false;
        iccIdQueryState = -1;
        mIccId = null;
        mImsi = null;
        mSpNameInEfSpn = null;

    }
    private void fetchEccList() {
        if (DBG) log("fetchEccList()");
        mEfEcc = "";

        if (mParentApp.getType() == AppType.APPTYPE_USIM) {
            mFh.loadEFLinearFixedAll(EF_ECC, obtainMessage(EVENT_GET_USIM_ECC_DONE));
        } else {
            mFh.loadEFTransparent(EF_ECC, obtainMessage(EVENT_GET_SIM_ECC_DONE));
        }
    }

    //ALPS00784072: We don't need to update configure if mnc & mnc not changed.
    private void updateConfiguration(String numeric) {
        if (!TextUtils.isEmpty(numeric) && !mOldMccMnc.equals(numeric)) {
            mOldMccMnc = numeric;
            MccTable.updateMccMncConfiguration(mContext, mOldMccMnc, false);
        } else {
            log("Do not update configuration if mcc mnc no change.");
        }
    }

    /**
    *parse pnn list
    */
    private void parseEFpnn(ArrayList messages) {
        int count = messages.size();
        if (DBG) log("parseEFpnn(): pnn has " + count + " records");

        mPnnNetworkNames = new ArrayList<OperatorName>(count);
        for (int i = 0; i < count; i++) {
            byte[] data = (byte[]) messages.get(i);
            if (DBG) log("parseEFpnn(): pnn record " + i + " content is " + IccUtils.bytesToHexString(data));

            SimTlv tlv = new SimTlv(data, 0, data.length);
            OperatorName opName = new OperatorName();
            for (; tlv.isValidObject(); tlv.nextObject()) {
                if (tlv.getTag() == TAG_FULL_NETWORK_NAME) {
                    opName.sFullName = IccUtils.networkNameToString(
                                tlv.getData(), 0, tlv.getData().length);
                    if (DBG) log("parseEFpnn(): pnn sFullName is "  + opName.sFullName);
                } else if (tlv.getTag() == TAG_SHORT_NETWORK_NAME) {
                    opName.sShortName = IccUtils.networkNameToString(
                                tlv.getData(), 0, tlv.getData().length);
                    if (DBG) log("parseEFpnn(): pnn sShortName is "  + opName.sShortName);
                }
            }

            mPnnNetworkNames.add(opName);
        }
    }

    // ALPS00267605 : PNN/OPL revision
    private void fetchPnnAndOpl() {
        if (DBG) log("fetchPnnAndOpl()");
        //boolean bPnnOplActive = false;
        boolean bPnnActive = false;
        boolean bOplActive = false;

        if (mEfSST != null) {
            if (mParentApp.getType() == AppType.APPTYPE_USIM) {
                if (mEfSST.length >= 6) {
                    bPnnActive = ((mEfSST[5] & 0x10) == 0x10);
                    if (bPnnActive) {
                        bOplActive = ((mEfSST[5] & 0x20) == 0x20);
                    }
                }
            } else if (mEfSST.length >= 13) {
                bPnnActive = ((mEfSST[12] & 0x30) == 0x30);
                if (bPnnActive) {
                    bOplActive = ((mEfSST[12] & 0xC0) == 0xC0);
                }
            }
        }
        if (DBG) log("bPnnActive = " + bPnnActive + ", bOplActive = " + bOplActive);

        if (bPnnActive) {
            mFh.loadEFLinearFixedAll(EF_PNN, obtainMessage(EVENT_GET_PNN_DONE));
            mRecordsToLoad++;
            if (bOplActive) {
                mFh.loadEFLinearFixedAll(EF_OPL, obtainMessage(EVENT_GET_ALL_OPL_DONE));
                mRecordsToLoad++;
            }
        }
    }

    private void fetchSpn() {
        if (DBG) log("fetchSpn()");
        boolean bSpnActive = false;

        Phone.IccServiceStatus iccSerStatus =  getSIMServiceStatus(Phone.IccService.SPN);
        if (iccSerStatus == Phone.IccServiceStatus.ACTIVATED) {
            setServiceProviderName(null);
            mFh.loadEFTransparent(EF_SPN,
                    obtainMessage(EVENT_GET_SPN_DONE));
            mRecordsToLoad++;
        } else {
            if (DBG) log("[SIMRecords] SPN service is not activated  ");
        }
    }

    public Phone.IccServiceStatus getSIMServiceStatus(Phone.IccService enService) {
        int nServiceNum = enService.getIndex();
        Phone.IccServiceStatus simServiceStatus = Phone.IccServiceStatus.UNKNOWN;
        if (DBG) log("getSIMServiceStatus enService is " + enService + " Service Index is " + nServiceNum);

        if (nServiceNum >= 0 && nServiceNum < Phone.IccService.UNSUPPORTED_SERVICE.getIndex() && mEfSST != null) {
            if (mParentApp.getType() == AppType.APPTYPE_USIM) {
                int nUSTIndex = usimServiceNumber[nServiceNum];
                if (nUSTIndex <= 0) {
                    simServiceStatus = Phone.IccServiceStatus.NOT_EXIST_IN_USIM;
                } else {
                    int nbyte = nUSTIndex / 8;
                    int nbit = nUSTIndex % 8 ;
                    if (nbit == 0) {
                        nbit = 7;
                        nbyte--;
                    } else {
                        nbit--;
                    }
                    if (DBG) log("getSIMServiceStatus USIM nbyte: " + nbyte + " nbit: " + nbit);

                    if (mEfSST.length > nbyte && ((mEfSST[nbyte] & (0x1 << nbit)) > 0)) {
                        simServiceStatus = Phone.IccServiceStatus.ACTIVATED;
                    } else {
                        simServiceStatus = Phone.IccServiceStatus.INACTIVATED;
                    }
                }
            } else {
                int nSSTIndex = simServiceNumber[nServiceNum];
                if (nSSTIndex <= 0) {
                    simServiceStatus = Phone.IccServiceStatus.NOT_EXIST_IN_SIM;
                } else {
                    int nbyte = nSSTIndex / 4;
                    int nbit = nSSTIndex % 4;
                    if (nbit == 0) {
                        nbit = 3;
                        nbyte--;
                    } else {
                        nbit--;
                    }

                    int nMask = (0x2 << (nbit * 2));
                    log("getSIMServiceStatus SIM nbyte: " + nbyte + " nbit: " + nbit + " nMask: " + nMask);
                    if (mEfSST.length > nbyte && ((mEfSST[nbyte] & nMask) == nMask)) {
                        simServiceStatus = Phone.IccServiceStatus.ACTIVATED;
                    } else {
                        simServiceStatus = Phone.IccServiceStatus.INACTIVATED;
                    }
                }
            }
        }

        log("getSIMServiceStatus simServiceStatus: " + simServiceStatus);
        return simServiceStatus;
    }

    private void fetchSmsp() {
        if (DBG) log("fetchSmsp()");

        //For USim authentication.
        if (mUsimServiceTable != null && mParentApp.getType() != AppType.APPTYPE_SIM) {
            if (mUsimServiceTable.isAvailable(UsimServiceTable.UsimService.SM_SERVICE_PARAMS)) {
                if (DBG) log("SMSP support.");
                mFh.loadEFLinearFixed(EF_SMSP, 1, obtainMessage(EVENT_GET_SMSP_DONE));
                mRecordsToLoad++;

                if (mUsimServiceTable.isAvailable(UsimServiceTable.UsimService.SM_OVER_IP)) {
                    if (DBG) log("PSISMSP support.");
                    mFh.loadEFLinearFixed(EF_PSISMSC, 1, obtainMessage(EVENT_GET_PSISMSC_DONE));
                    mRecordsToLoad++;
                }

            }
        }
    }

    private void fetchGbaRecords() {
        if (DBG) log("fetchGbaRecords");

        if (mUsimServiceTable != null && mParentApp.getType() != AppType.APPTYPE_SIM) {
            if (mUsimServiceTable.isAvailable(UsimServiceTable.UsimService.GBA)) {
                if (DBG) log("GBA support.");
                mFh.loadEFTransparent(EF_ISIM_GBABP, obtainMessage(EVENT_GET_GBABP_DONE));
                mRecordsToLoad++;

                mFh.loadEFLinearFixedAll(EF_ISIM_GBANL, obtainMessage(EVENT_GET_GBANL_DONE));
                mRecordsToLoad++;
            }
        }
    }

    private void fetchMwisRecords() {
        if (DBG) log("fetchMwisRecords");

        if (mUsimServiceTable != null && mParentApp.getType() != AppType.APPTYPE_SIM) {
            if (mUsimServiceTable.isAvailable(UsimServiceTable.UsimService.MWI_STATUS)) {
                if (DBG) log("MWIS support.");
                mFh.loadEFLinearFixed(EF_MWIS, 1, obtainMessage(EVENT_GET_MWIS_DONE));
                mRecordsToLoad++;
            }
        }
    }

    /**
    *parse opl list
    */
    private void parseEFopl(ArrayList messages) {
        int count = messages.size();
        if (DBG) log("parseEFopl(): opl has " + count + " records");

        mOperatorList = new ArrayList<OplRecord>(count);
        for (int i = 0; i < count; i++) {
            byte[] data = (byte[]) messages.get(i);
            if (DBG) log("parseEFopl(): opl record " + i + " content is " + IccUtils.bytesToHexString(data));

            OplRecord oplRec = new OplRecord();

            oplRec.sPlmn = IccUtils.parsePlmnToStringForEfOpl(data, 0, 3); // ALPS00316057
            if (DBG) log("parseEFopl(): opl sPlmn = " + oplRec.sPlmn);

            byte[] minLac = new byte[2];
            minLac[0] = data[3];
            minLac[1] = data[4];
            oplRec.nMinLAC = Integer.parseInt(IccUtils.bytesToHexString(minLac), 16);
            if (DBG) log("parseEFopl(): opl nMinLAC = " + oplRec.nMinLAC);

            byte[] maxLAC = new byte[2];
            maxLAC[0] = data[5];
            maxLAC[1] = data[6];
            oplRec.nMaxLAC = Integer.parseInt(IccUtils.bytesToHexString(maxLAC), 16);
            if (DBG) log("parseEFopl(): opl nMaxLAC = " + oplRec.nMaxLAC);

            oplRec.nPnnIndex = Integer.parseInt(IccUtils.bytesToHexString(data).substring(14), 16);
            if (DBG) log("parseEFopl(): opl nPnnIndex = " + oplRec.nPnnIndex);

            mOperatorList.add(oplRec);
        }
    }

    private void boradcastEfRatContentNotify(int item) {
        // TO DO MR1
/*
        if(mPhone.get3GCapabilitySIM() != mSlotId) {
            log("not broadCast intent ACTION_EF_RAT_CONTENT_NOTIFY, simId: " + mSimId + ", 3GslotId: " + mPhone.get3GCapabilitySIM());
            return;
        }
*/
        Intent intent = new Intent(TelephonyIntents.ACTION_EF_RAT_CONTENT_NOTIFY);
        intent.putExtra(TelephonyIntents.EXTRA_EF_RAT_STATUS, item);
        intent.putExtra(PhoneConstants.SLOT_KEY, mSlotId);
        log("broadCast intent ACTION_EF_RAT_CONTENT_NOTIFY: item: " + item + ", simId: " + mSlotId);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE, UserHandle.USER_ALL);
    }

    // ALPS00302698 ENS
    private void processEfCspPlmnModeBitUrc(int bit) {
        log("processEfCspPlmnModeBitUrc: bit = " + bit);
        if (bit == 0) {
            mCspPlmnEnabled = false;
        } else {
            mCspPlmnEnabled = true;
        }
////        phone.setNetworkSelectionModeAutomatic(null);

        Intent intent = new Intent(TelephonyIntents.ACTION_EF_CSP_CONTENT_NOTIFY);
        intent.putExtra(TelephonyIntents.EXTRA_PLMN_MODE_BIT, bit);
        intent.putExtra(PhoneConstants.SLOT_KEY, mSlotId);
        log("broadCast intent ACTION_EF_CSP_CONTENT_NOTIFY, EXTRA_PLMN_MODE_BIT: " +  bit);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE, UserHandle.USER_ALL);

    }

    private void fetchLanguageIndicator() {
        log("fetchLanguageIndicator ");
        String l = SystemProperties.get("persist.sys.language");
        String c = SystemProperties.get("persist.sys.country");
        String oldSimLang = SystemProperties.get("persist.sys.simlanguage");
        if ((null == l || 0 == l.length()) && (null == c || 0 == c.length())
                         && (null == oldSimLang || 0 == oldSimLang.length())) {
            if (mEfLi == null) {
                mFh.loadEFTransparent(EF_LI,
                       obtainMessage(EVENT_GET_LI_DONE));
                efLanguageToLoad++;
            }
            mFh.loadEFTransparent(EF_ELP,
                   obtainMessage(EVENT_GET_ELP_DONE));
            efLanguageToLoad++;
        }
    }

    private void onLanguageFileLoaded() {
        efLanguageToLoad--;
        log("onLanguageFileLoaded efLanguageToLoad is " + efLanguageToLoad);
        if (efLanguageToLoad == 0) {
            log("onLanguageFileLoaded all language file loaded");
            if (mEfLi != null || mEfELP != null) {
                setLanguageFromSIM();
            } else {
                log("onLanguageFileLoaded all language file are not exist!");
            }
        }
    }

    private void setLanguageFromSIM() {
        log("setLanguageFromSIM ");
        boolean bMatched = false;

        if (mParentApp.getType() == AppType.APPTYPE_USIM) {
            bMatched = getMatchedLocaleByLI(mEfLi);
        } else {
            bMatched = getMatchedLocaleByLP(mEfLi);
        }
        if (!bMatched && mEfELP != null) {
            bMatched = getMatchedLocaleByLI(mEfELP);
        }
        log("setLanguageFromSIM End");
    }

    private boolean getMatchedLocaleByLI(byte[] data) {
        boolean ret = false;
        if (data == null) {
            return ret;
        }
        int lenOfLI = data.length;
        String lang = null;
        for (int i = 0; i + 2 <= lenOfLI; i += 2) {
            lang = IccUtils.parseLanguageIndicator(data, i, 2);
            log("USIM language in language indicator: i is " + i + " language is " + lang);
            if (lang == null || lang.equals("")) {
                log("USIM language in language indicator: i is " + i + " language is empty");
                break;
            }
            lang = lang.toLowerCase();
            ret = matchLangToLocale(lang);

            if (ret) {
                break;
            }
        }
        return ret;
    }

    private boolean getMatchedLocaleByLP(byte[] data) {
        boolean ret = false;
        if (data == null) {
            return ret;
        }
        int lenOfLP = data.length;
        String lang = null;
        for (int i = 0; i < lenOfLP; i++) {
            int index = (int) mEfLi[0] & 0xff;
            if (0x00 <= index && index <= 0x0f) {
                lang = LANGUAGE_CODE_FOR_LP[index];
            } else if (0x20 <= index && index <= 0x2f) {
                lang = LANGUAGE_CODE_FOR_LP[index - 0x10];
            }

            log("SIM language in language preference: i is " + i + " language is " + lang);
            if (lang == null || lang.equals("")) {
                log("SIM language in language preference: i is " + i + " language is empty");
                break;
            }

            ret = matchLangToLocale(lang);

            if (ret) {
                break;
            }
        }
        return ret;
    }

    private boolean matchLangToLocale(String lang) {
        boolean ret = false;
        String[] locals = mContext.getAssets().getLocales();
        int localsSize = locals.length;
        for (int i = 0 ; i < localsSize; i++) {
            String s = locals[i];
            int len = s.length();
            if (len == 5) {
                String language = s.substring(0, 2);
                log("Supported languages: the i" + i + " th is " + language);
                if (lang.equals(language)) {
                    ret = true;
/* TEMP
                    setSystemLocale(lang, s.substring(3, 5), true);
*/
                    MccTable.setSystemLocale(mContext, lang, s.substring(3, 5));
                    log("Matched! lang: " + lang + ", country is " + s.substring(3, 5));
                    break;
                }
            }
        }
        return ret;
    }

    /*
      Detail description:
      This feature provides a interface to get menu title string from EF_SUME
    */
    public String getMenuTitleFromEf() {
        return mMenuTitleFromEf;
    }

    private void fetchCPHSOns() {
        if (DBG) log("fetchCPHSOns()");
        cphsOnsl = null;
        cphsOnss = null;
        mFh.loadEFTransparent(EF_SPN_CPHS,
               obtainMessage(EVENT_GET_CPHSONS_DONE));
        mRecordsToLoad++;
        mFh.loadEFTransparent(
               EF_SPN_SHORT_CPHS, obtainMessage(EVENT_GET_SHORT_CPHSONS_DONE));
        mRecordsToLoad++;
    }

    // ALPS00302702 RAT balancing START
    private void fetchRatBalancing() {
        // TODO: wait for isSetLanguageBySIM ready
        if (mTelephonyExt.isSetLanguageBySIM())
            return;
        log("support MTK_RAT_BALANCING");

        if (mParentApp.getType() == AppType.APPTYPE_USIM) {
            log("start loading EF_RAT");
            mFh.loadEFTransparent(EF_RAT, obtainMessage(EVENT_GET_RAT_DONE));
            mRecordsToLoad++;
        }
        else if (mParentApp.getType() == AppType.APPTYPE_SIM) {
            // broadcast & set no file
            log("loading EF_RAT fail, because of SIM");
            mEfRatLoaded = false;
            mEfRat = null;
            boradcastEfRatContentNotify(EF_RAT_FOR_OTHER_CASE);
        }
        else {
            log("loading EF_RAT fail, because of +EUSIM");
        }
    }

    public int getEfRatBalancing() {
        log("getEfRatBalancing: iccCardType = " + mParentApp.getType()
                + ", mEfRatLoaded = " + mEfRatLoaded + ", mEfRat is null = " + (mEfRat == null));

        if ((mParentApp.getType() == AppType.APPTYPE_USIM) && mEfRatLoaded && mEfRat == null) {
            return EF_RAT_NOT_EXIST_IN_USIM;
        }
        return EF_RAT_FOR_OTHER_CASE;
    }
    // ALPS00302702 RAT balancing END

    public boolean isHPlmn(String plmn) {
        ServiceStateTracker sst = null;
        if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()) {
            sst = ((PhoneBase)mPhone).getServiceStateTracker();
        } else {
            sst = ((PhoneBase) ((PhoneProxy) mPhone).getActivePhone())
                .getServiceStateTracker();
        }
        if (sst != null) {
            return sst.isHPlmn(plmn);
        } else {
            if (DBG) log("can't get sst");
            return false;
        }
    }

    // ALPS00359372 for at&t testcase, mnc 2 should match 3 digits
    private boolean isMatchingPlmnForEfOpl(String simPlmn, String bcchPlmn) {
        if (simPlmn == null || simPlmn.equals("") || bcchPlmn == null || bcchPlmn.equals(""))
            return false;

        if (DBG) log("isMatchingPlmnForEfOpl(): simPlmn = " + simPlmn + ", bcchPlmn = " + bcchPlmn);

        /*  3GPP TS 23.122 Annex A (normative): HPLMN Matching Criteria
            For PCS1900 for North America, regulations mandate that a 3-digit MNC shall be used;
            however during a transition period, a 2 digit MNC may be broadcast by the Network and,
            in this case, the 3rd digit of the SIM is stored as 0 (this is the 0 suffix rule).     */
        int simPlmnLen = simPlmn.length();
        int bcchPlmnLen = bcchPlmn.length();
        if (simPlmnLen < 5 || bcchPlmnLen < 5)
            return false;

        int i = 0;
        for (i = 0; i < 5; i++) {
            if (simPlmn.charAt(i) == 'd')
                continue;
            if (simPlmn.charAt(i) != bcchPlmn.charAt(i))
                return false;
        }

        if (simPlmnLen == 6 && bcchPlmnLen == 6) {
            if (simPlmn.charAt(5) == 'd' || simPlmn.charAt(5) == bcchPlmn.charAt(5)) {
                return true;
            } else {
                return false;
            }
        } else if (bcchPlmnLen == 6 && bcchPlmn.charAt(5) != '0' && bcchPlmn.charAt(5) != 'd') {
            return false;
        } else if (simPlmnLen == 6 && simPlmn.charAt(5) != '0' && simPlmn.charAt(5) != 'd') {
            return false;
        }

        return true;
    }

    // ALPS00267605 : PNN/OPL revision
    public String getEonsIfExist(String plmn, int nLac, boolean bLongNameRequired) {
        if (DBG) log("EONS getEonsIfExist: plmn is " + plmn + " nLac is " + nLac + " bLongNameRequired: " + bLongNameRequired);
        if (plmn == null || mPnnNetworkNames == null || mPnnNetworkNames.size() == 0) {
            return null;
        }

        int nPnnIndex = -1;
        boolean isHPLMN = isHPlmn(plmn);

        if (mOperatorList == null) {
            // case for EF_PNN only
            if (isHPLMN) {
                if (DBG) log("EONS getEonsIfExist: Plmn is HPLMN, but no mOperatorList, return PNN's first record");
                nPnnIndex = 1;
            } else {
                if (DBG) log("EONS getEonsIfExist: Plmn is not HPLMN, and no mOperatorList, return null");
                return null;
            }
        } else {
            //search EF_OPL using plmn & nLac
            for (int i = 0; i < mOperatorList.size(); i++) {
                OplRecord oplRec = mOperatorList.get(i);
                if (DBG) log("EONS getEonsIfExist: record number is " + i + " sPlmn: " + oplRec.sPlmn + " nMinLAC: "
                             + oplRec.nMinLAC + " nMaxLAC: " + oplRec.nMaxLAC + " PnnIndex " + oplRec.nPnnIndex);

                // ALPS00316057
                //if((plmn.equals(oplRec.sPlmn) ||(!oplRec.sPlmn.equals("") && plmn.startsWith(oplRec.sPlmn))) &&
                if (isMatchingPlmnForEfOpl(oplRec.sPlmn, plmn) &&
                   ((oplRec.nMinLAC == 0 && oplRec.nMaxLAC == 0xfffe) || (oplRec.nMinLAC <= nLac && oplRec.nMaxLAC >= nLac))) {
                    if (DBG) log("EONS getEonsIfExist: find it in EF_OPL");
                    if (oplRec.nPnnIndex == 0) {
                        if (DBG) log("EONS getEonsIfExist: oplRec.nPnnIndex is 0 indicates that the name is to be taken from other sources");
                        return null;
                    }
                    nPnnIndex = oplRec.nPnnIndex;
                    break;
                }
            }
        }

        //ALPS00312727, 11603, add check (mOperatorList.size() == 1
        /*if (nPnnIndex == -1 && isHPLMN && (mOperatorList.size() == 1)) {
            if (DBG) log("EONS getEonsIfExist: not find it in EF_OPL, but Plmn is HPLMN, return PNN's first record");
            nPnnIndex = 1;
        }
        else if (nPnnIndex > 1 && nPnnIndex > mPnnNetworkNames.size() && isHPLMN) {
            if (DBG) log("EONS getEonsIfExist: find it in EF_OPL, but index in EF_OPL > EF_PNN list length & Plmn is HPLMN, return PNN's first record");
            nPnnIndex = 1;
        }
        else if (nPnnIndex > 1 && nPnnIndex > mPnnNetworkNames.size() && !isHPLMN) {
            if (DBG) log("EONS getEonsIfExist: find it in EF_OPL, but index in EF_OPL > EF_PNN list length & Plmn is not HPLMN, return PNN's first record");
            nPnnIndex = -1;
        }*/

	//modified by maolikui at 2015-09-19 start
        if (nPnnIndex == -1 || nPnnIndex > mPnnNetworkNames.size()) { 
            if (DBG) log("EONS getEonsIfExist: not find it in EF_OPL, or index in EF_OPL > EF_PNN list length, return NULL"); 
            nPnnIndex = -1; 
        } 
	//modified by maolikui at 2015-09-19 end

        String sEons = null;
        if (nPnnIndex >= 1) {
            OperatorName opName = mPnnNetworkNames.get(nPnnIndex - 1);
            if (bLongNameRequired) {
                if (opName.sFullName != null) {
                    sEons = new String(opName.sFullName);
                } else if (opName.sShortName != null) {
                    sEons = new String(opName.sShortName);
                }
            } else if (!bLongNameRequired) {
                if (opName.sShortName != null) {
                    sEons = new String(opName.sShortName);
                } else if (opName.sFullName != null) {
                    sEons = new String(opName.sFullName);
                }
            }
        }
        if (DBG) log("EONS getEonsIfExist: sEons is " + sEons);

        return sEons;

        /*int nPnnIndex = -1;
        //check if the plmn is Hplmn, return the first record of pnn
        if (isHPlmn(plmn)) {
            nPnnIndex = 1;
            if (DBG) log("EONS getEonsIfExist Plmn is hplmn");
        } else {
            //search the plmn from opl and if the LAC in the range of opl
            for (int i = 0; i < mOperatorList.size(); i++) {
                OplRecord oplRec = mOperatorList.get(i);
                //check if the plmn equals with the plmn in the operator list or starts with the plmn in the operator list(which include wild char 'D')
                if((plmn.equals(oplRec.sPlmn) ||(!oplRec.sPlmn.equals("") && plmn.startsWith(oplRec.sPlmn))) &&
                   ((oplRec.nMinLAC == 0 && oplRec.nMaxLAC == 0xfffe) || (oplRec.nMinLAC <= nLac && oplRec.nMaxLAC >= nLac))) {
                    nPnnIndex = oplRec.nPnnIndex;
                    break;
                }
                if (DBG) log("EONS getEonsIfExist record number is " + i + " sPlmn: " + oplRec.sPlmn + " nMinLAC: "
                             + oplRec.nMinLAC + " nMaxLAC: " + oplRec.nMaxLAC + " PnnIndex " + oplRec.nPnnIndex);
            }
            if (nPnnIndex == 0) {
                return null;    // not HPLMN and the index is 0 indicates that the name is to be taken from other sources
            }
        }
        if (DBG) log("EONS getEonsIfExist Index of pnn is  " + nPnnIndex);

        String sEons = null;
        if (nPnnIndex >= 1) {
            OperatorName opName = mPnnNetworkNames.get(nPnnIndex - 1);
            if (bLongNameRequired) {
                if (opName.sFullName != null) {
                    sEons = new String(opName.sFullName);
                } else if (opName.sShortName != null) {
                    sEons = new String(opName.sShortName);
                }
            } else if (!bLongNameRequired ) {
                if (opName.sShortName != null) {
                    sEons = new String(opName.sShortName);
                } else if (opName.sFullName != null) {
                    sEons = new String(opName.sFullName);
                }
            }
        }
        if (DBG) log("EONS getEonsIfExist sEons is " + sEons);
        return sEons;*/
    }


    /**
     * Returns the GBA bootstrapping parameters (GBABP) that was loaded from the USIM.
     * @return GBA bootstrapping parameters or null if not present or not loaded
     */
    public String getEfGbabp() {
        log("GBABP = " + mGbabp);
        return mGbabp;
    }

    /**
     * Set the GBA bootstrapping parameters (GBABP) value into the USIM.
     * @param gbabp a GBA bootstrapping parameters value in String type
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setEfGbabp(String gbabp, Message onComplete) {
        byte[] data = IccUtils.hexStringToBytes(gbabp);

        log("setEfGbabp data = " + data);
        mFh.updateEFTransparent(EF_GBABP, data, onComplete);
    }

    /**
     * Returns the Public Service Identity of the SM-SC (PSISMSC) that was loaded from the USIM.
     * @return PSISMSC or null if not present or not loaded
     */
    public byte[] getEfPsismsc() {
        log("PSISMSC = " + mEfPsismsc);
        return mEfPsismsc;
    }

    /**
     * Returns the Short message parameter (SMSP) that was loaded from the USIM.
     * @return PSISMSC or null if not present or not loaded
     */
    public byte[] getEfSmsp() {
        log("mEfSmsp = " + mEfPsismsc);
        return mEfSmsp;
    }

    /**
     * Returns the MCC+MNC length that was loaded from the USIM.
     * @return MCC+MNC length or 0 if not present or not loaded
     */
    public int getMncLength() {
        log("mncLength = " + mMncLength);
        return mMncLength;
    }

    private class RebootClickListener
            implements DialogInterface.OnClickListener {

        @Override
        public void onClick(DialogInterface dialog, int which) {
            log("Unlock Phone onClick");
            PowerManager pm = (PowerManager) mContext
                    .getSystemService(Context.POWER_SERVICE);
            pm.reboot("Unlock state changed");
        }
    }

    public void broadcastPhbStateChangedIntent(boolean isReady) {
        log("broadcastPhbStateChangedIntent, mPhbReady " + mPhbReady + ", mSIMInfoReady " + mSIMInfoReady);
        int subId = SubscriptionManager.getSubIdUsingPhoneId(mParentApp.getPhoneId());
        if (subId <= 0) {
            log("broadcastPhbStateChangedIntent, subId <= 0");
            mPhbWaitSub = true;
        } else {
            Intent intent = new Intent(TelephonyIntents.ACTION_PHB_STATE_CHANGED);
            intent.putExtra("ready", isReady);
            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
            if (DBG) log("Broadcasting intent ACTION_PHB_STATE_CHANGED " + isReady
                        + " sub id " + mParentApp.getPhoneId());
            mContext.sendBroadcast(intent);
        }
    }

    public boolean isPhbReady() {
        if (DBG) log("isPhbReady(): cached mPhbReady = " + (mPhbReady ? "true" : "false"));
        String strPhbReady = "false";
        String strAllSimState = "";
        String strCurSimState = "";
        boolean isSimLocked = false;
        int phoneId = mParentApp.getPhoneId();

        strPhbReady = SystemProperties.get(SIMRECORD_PROPERTY_RIL_PHB_READY[mParentApp.getSlotId()], "false");
        strAllSimState = SystemProperties.get(TelephonyProperties.PROPERTY_SIM_STATE);

        if ((strAllSimState != null) && (strAllSimState.length() > 0)) {
            String values[] = strAllSimState.split(",");
            if ((phoneId >= 0) && (phoneId < values.length) && (values[phoneId] != null)) {
                strCurSimState = values[phoneId];
            }
        }

        isSimLocked = (strCurSimState.equals("NETWORK_LOCKED") || strCurSimState.equals("PIN_REQUIRED")); //In PUK_REQUIRED state, phb can be accessed.
	//modified by maolikui at 2015-09-19 start
        /*if (strPhbReady.equals("true") && false == isSimLocked) {
            mPhbReady = true;
        } else {
            mPhbReady = false;
        }*/
          
        if (DBG) log("isPhbReady(): mPhbReady = " + (mPhbReady ? "true" : "false") + ", strCurSimState = " + strCurSimState);
        //return mPhbReady;
	return (strPhbReady.equals("true") && false == isSimLocked)? true: false;
	//modified by maolikui at 2015-09-19 end
    }

    public void setPhbReady(boolean isReady) {
        if (DBG) log("setPhbReady(): isReady = " + (isReady ? "true" : "false"));
       
         //if (mPhbReady != isReady) {//modified by maolikui at 2015-09-19 
            String strPhbReady = isReady ? "true" : "false";
            mPhbReady = isReady;
            SystemProperties.set(SIMRECORD_PROPERTY_RIL_PHB_READY[mParentApp.getSlotId()], strPhbReady);
            broadcastPhbStateChangedIntent(mPhbReady);
        //}//modified by maolikui at 2015-09-19 
    }
    // add for alps01947090
    public boolean isRadioAvailable() {
        if (mCi != null) {
          return mCi.getRadioState().isAvailable();
        }
        return false;
    }

    // Added by M end

    /** M: Bug Fix for ALPS02189616. */
    // add new code for svlte romaing case.
    private static final int EVENT_DELAYED_SEND_PHB_CHANGE = 200;
    private RadioTechnologyChangedReceiver mRTC;
    /**
     * Inner private class for revice broad cast ACTION_RADIO_TECHNOLOGY_CHANGED.
     */
    private class RadioTechnologyChangedReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
             if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                 // listener radio technology changed. If it not own object phone
                 // broadcast false.
                 // if it own object, send delay message to broadcast PHB_CHANGE
                 // event. APP will receive PHB_CHANGE broadcast and init phonebook.
                 int phoneid = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
                 log("[ACTION_RADIO_TECHNOLOGY_CHANGED] phoneid : " + phoneid);
                 if (null != mParentApp && mParentApp.getPhoneId() == phoneid) {
                     String cdmaPhoneName = "CDMA";
                     int delayedTime = 500;
                     String activePhoneName = intent.getStringExtra(PhoneConstants.PHONE_NAME_KEY);
                     int subid = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY, -1);
                     log("[ACTION_RADIO_TECHNOLOGY_CHANGED] activePhoneName : " + activePhoneName
                             + " | subid : " + subid);
                     if (!cdmaPhoneName.equals(activePhoneName)) {
                         sendMessageDelayed(obtainMessage(EVENT_DELAYED_SEND_PHB_CHANGE),
                                 delayedTime);
                         mAdnCache.reset();
                     }
                 }
             }
        }
    }


}
