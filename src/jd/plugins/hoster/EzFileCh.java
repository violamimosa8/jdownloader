//    jDownloader - Downloadmanager
//    Copyright (C) 2015  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JComponent;
import javax.swing.JLabel;

import jd.PluginWrapper;
import jd.config.Property;
import jd.gui.swing.components.linkbutton.JLink;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtPasswordField;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.plugins.accounts.AccountFactory;
import org.jdownloader.plugins.accounts.EditAccountPanel;
import org.jdownloader.plugins.accounts.Notifier;

@HostPlugin(revision = "$Revision: 29998 $", interfaceVersion = 3, names = { "ezfile.ch" }, urls = { "https?://(www\\.)?ezfile\\.ch/[a-z0-9]{7,9}" }, flags = { 2 })
public class EzFileCh extends PluginForHost {

    private final String         useragent                    = "JDownloader";

    /* Connection stuff */
    private static final boolean FREE_RESUME                  = true;
    private static final int     FREE_MAXCHUNKS               = 1;
    private static final int     FREE_MAXDOWNLOADS            = 1;
    private static final boolean ACCOUNT_FREE_RESUME          = true;
    private static final int     ACCOUNT_FREE_MAXCHUNKS       = 1;
    private static final int     ACCOUNT_FREE_MAXDOWNLOADS    = 1;
    private static final boolean ACCOUNT_PREMIUM_RESUME       = true;
    private static final int     ACCOUNT_PREMIUM_MAXCHUNKS    = 1;
    private static final int     ACCOUNT_PREMIUM_MAXDOWNLOADS = 10;

    private static final String  NOCHUNKS                     = "NOCHUNKS";
    private static final String  NORESUME                     = "NORESUME";
    public static final String   MAINPAGE                     = "https://ezfile.ch";
    private static AtomicInteger maxPrem                      = new AtomicInteger(1);
    private static AtomicBoolean UNDERMAINTENANCE             = new AtomicBoolean(false);
    private static final String  UNDERMAINTENANCEUSERTEXT     = "The site is under maintenance!";

    private static final String  API_ERROR_NO_PERMISSION      = "No permission granted for this apikey to perform this api call";

    /* API doc: https://ezfile.ch/?m=apidoc */
    // private static final String NICE_HOST = "ezfile.ch";
    private static final boolean useFilecheckAPI              = true;

    private String               dllink                       = null;
    private boolean              isPrivateFile                = false;

    @SuppressWarnings("deprecation")
    public EzFileCh(final PluginWrapper wrapper) {
        super(wrapper);
        this.setAccountwithoutUsername(true);
        this.enablePremium(MAINPAGE + "/user-register.html");
    }

    @Override
    public AccountFactory getAccountFactory() {
        return new EzFileChAccountFactory();
    }

    /**
     * JD2 CODE. DO NOT USE OVERRIDE FOR JD=) COMPATIBILITY REASONS!
     */
    public boolean isProxyRotationEnabledForLinkChecker() {
        return false;
    }

    @Override
    public String getAGBLink() {
        return MAINPAGE + "/?m=help&a=tos";
    }

    @SuppressWarnings({ "deprecation", "unchecked" })
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        dllink = null;
        isPrivateFile = false;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        prepBrowser(br);
        String filename = null;
        String filesize = null;
        downloadLink.setLinkID(new Regex(downloadLink.getDownloadURL(), "([a-z0-9]+)$").getMatch(0));
        if (useFilecheckAPI) {
            br.getPage("https://ezfile.ch/?m=api&a=check_file&fkey=" + downloadLink.getLinkID());
            final LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(br.toString());
            final Object filesizeo = entries.get("fsize");
            final String status = (String) entries.get("status");
            final String message = (String) entries.get("message");
            final String ftype = (String) entries.get("ftype");
            filename = (String) entries.get("fname");
            if (message != null && message.equals("private file")) {
                /* We cannot get filename/size for this case but we know that the file is online. */
                isPrivateFile = true;
                downloadLink.getLinkStatus().setStatusText("This is a private file which can only be downloaded by its owner");
                return AvailableStatus.TRUE;
            } else if (!"ok".equals(status)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (filename == null || filesizeo == null || ftype == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            downloadLink.setFinalFileName(filename);
            downloadLink.setDownloadSize(jd.plugins.hoster.DummyScriptEnginePlugin.toLong(filesizeo, -1));
            /* 0=normal, 2=directdownload */
            if (ftype.equals("2")) {
                dllink = downloadLink.getDownloadURL();
            }
        } else {
            final boolean isfollowingRedirect = br.isFollowingRedirects();
            // clear old browser
            br = prepBrowser(new Browser());
            // can be direct link!
            URLConnectionAdapter con = null;
            br.setFollowRedirects(true);
            try {
                con = br.openGetConnection(downloadLink.getDownloadURL());
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    if (con.getResponseCode() == 503) {
                        // they are using cloudflare these days!
                        // downloadLink.getLinkStatus().setStatusText(UNDERMAINTENANCEUSERTEXT);
                        // UNDERMAINTENANCE.set(true);
                        return AvailableStatus.UNCHECKABLE;
                    }
                    br.followConnection();
                } else {
                    downloadLink.setName(getFileNameFromHeader(con));
                    try {
                        // @since JD2
                        downloadLink.setVerifiedFileSize(con.getLongContentLength());
                    } catch (final Throwable t) {
                        downloadLink.setDownloadSize(con.getLongContentLength());
                    }
                    // lets also set dllink
                    dllink = br.getURL();
                    // set constants so we can save link, no point wasting this link!
                    return AvailableStatus.TRUE;
                }
            } finally {
                br.setFollowRedirects(isfollowingRedirect);
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
            if (br.containsHTML("The file at this URL was either removed or") || br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML(">This file is private")) {
                /* We cannot get filename/size for this case but we know that the file is online. */
                isPrivateFile = true;
                downloadLink.getLinkStatus().setStatusText("This is a private file which can only be downloaded by its owner");
                return AvailableStatus.TRUE;
            }
            final Regex finfo = br.getRegex("class=\"fa fa-file[a-z0-9\\- ]+\"></i>\\&nbsp;([^<>\"]*?) \\[(\\d+(?:,\\d+)?(?:\\.\\d{1,2})? [A-Za-z]{1,5})\\]</span>");
            filename = finfo.getMatch(0);
            filesize = finfo.getMatch(1);
            if (filename == null || filesize == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            filename = Encoding.htmlDecode(filename).trim();
            filesize = filesize.replace(",", "");
            downloadLink.setName(filename);
            downloadLink.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @SuppressWarnings("deprecation")
    public void doFree(final DownloadLink downloadLink, boolean viaAccount) throws Exception, PluginException {
        br.setFollowRedirects(true);
        final String fid = getFid(downloadLink);
        if (dllink == null) {
            dllink = checkDirectLink(downloadLink, "free_directlink");
        }
        if (dllink == null) {
            if (this.isPrivateFile) {
                /*
                 * TODO: Find a way to check if maybe the current user IS the owner of that file so we can actually download it instead of
                 * showing this error message! Free account + private file that the account owner owns is the only case that will not work
                 * with JDownloader due to a missing full login / API function (free account). According to the admin this case is so rare
                 * that we can ignore it.
                 */
                throw new PluginException(LinkStatus.ERROR_FATAL, "This is a private file which can only be downloaded by its owner");
            }
            if (useFilecheckAPI) {
                br.getPage(downloadLink.getDownloadURL());
            }
            if (br.containsHTML("You do not have enough traffic to continue<")) {
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, "This file can only be downloaded by premium users");
            } else if (br.containsHTML("Sign\\-in now with any of the options on the left if you wish to download this file")) {
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, "This file can only be downloaded by premium users");
            }
            final String f1 = br.getRegex("\\'f1\\':[\t\n\r ]*?\\'([^<>\"\\']*?)\\'").getMatch(0);
            final String f2 = br.getRegex("\\'f2\\':[\t\n\r ]*?\\'([^<>\"\\']*?)\\'").getMatch(0);
            if (f1 == null || f2 == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String postData = "fkey=" + fid + "&f1=" + f1 + "&f2=" + f2 + "&r=";
            final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
            postData += Encoding.urlEncode(recaptchaV2Response);
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.postPage("/?m=download&a=request", postData);
            handleErrorsAPI();
            this.dllink = getJson("downloadUrl");
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        br.setFollowRedirects(false);
        int chunks = FREE_MAXCHUNKS;
        boolean resume = FREE_RESUME;
        if (viaAccount) {
            chunks = ACCOUNT_FREE_MAXCHUNKS;
            resume = ACCOUNT_FREE_RESUME;
        }
        if (downloadLink.getBooleanProperty(NOCHUNKS, false) || resume == false) {
            chunks = 1;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resume, chunks);
        if (dl.getConnection().getContentType().contains("html")) {
            handleServerErrors();
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        }
        downloadLink.setProperty("free_directlink", dllink);
        if (!this.dl.startDownload()) {
            try {
                if (dl.externalDownloadStop()) {
                    return;
                }
            } catch (final Throwable e) {
            }
            if (downloadLink.getLinkStatus().getErrorMessage() != null && downloadLink.getLinkStatus().getErrorMessage().startsWith(JDL.L("download.error.message.rangeheaders", "Server does not support chunkload"))) {
                if (downloadLink.getBooleanProperty(NORESUME, false) == false) {
                    downloadLink.setChunksProgress(null);
                    downloadLink.setProperty(NORESUME, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            } else {
                /* unknown error, we disable multiple chunks */
                if (downloadLink.getBooleanProperty(NOCHUNKS, false) == false) {
                    downloadLink.setProperty(NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        if (UNDERMAINTENANCE.get()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, UNDERMAINTENANCEUSERTEXT);
        }
        doFree(downloadLink, false);
    }

    private void login(final Account account) throws Exception {
        br.setCookiesExclusive(true);
        br.setFollowRedirects(true);
        prepBrowser(br);
        accessAPI("https://ezfile.ch/?m=api&a=fetch_account_info&akey=" + Encoding.urlEncode(account.getPass()));
        final String message = getJson("message");
        if (message != null) {
            if (message.equals(API_ERROR_NO_PERMISSION)) {
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nDeinem APIKey fehlt die Berechtigung 'Allow Account info fetch'.\r\nHier kannst du diese aktivieren: ezfile.ch/?m=apidoc", PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nYour APIKey is missing the permission 'Allow Account info fetch'.\r\nYou can activate it here: ezfile.ch/?m=apidoc", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
            }
            if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen? Versuche folgendes:\r\n1. Falls dein Passwort Sonderzeichen enthält, ändere es (entferne diese) und versuche es erneut!\r\n2. Gib deine Zugangsdaten per Hand (ohne kopieren/einfügen) ein.", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nYou're sure that the username and password you entered are correct? Some hints:\r\n1. If your password contains special characters, change it (remove them) and try again!\r\n2. Type in your username/password by hand without copy & paste.", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        /* reset maxPrem workaround on every fetchaccount info */
        maxPrem.set(1);
        try {
            login(account);
        } catch (final PluginException e) {
            account.setValid(false);
            throw e;
        }
        long traffic_left_long = 0;
        final String traffic_left = getJson("bandwidth");
        traffic_left_long = SizeFormatter.getSize(traffic_left);
        if (traffic_left_long == 0) {
            ai.setStatus("Registered (free) account");
            account.setProperty("free", true);
            try {
                account.setType(AccountType.FREE);
                maxPrem.set(ACCOUNT_FREE_MAXDOWNLOADS);
                account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
                /* free accounts can still have captcha. */
                account.setConcurrentUsePossible(false);
            } catch (final Throwable e) {
            }
            /* No premium traffic means unlimited free traffic */
            ai.setUnlimitedTraffic();
        } else {
            ai.setStatus("Premium account");
            account.setProperty("free", false);
            try {
                account.setType(AccountType.PREMIUM);
                maxPrem.set(ACCOUNT_PREMIUM_MAXDOWNLOADS);
                account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
                account.setConcurrentUsePossible(true);
            } catch (final Throwable e) {
            }
            ai.setTrafficLeft(traffic_left_long);
        }
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        if (UNDERMAINTENANCE.get()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, UNDERMAINTENANCEUSERTEXT);
        }
        if (!account.getBooleanProperty("free", false)) {
            accessAPI("https://ezfile.ch/?m=api&a=download&akey=" + Encoding.urlEncode(account.getPass()) + "&fkey=" + link.getLinkID());
            final String message = getJson("message");
            if (API_ERROR_NO_PERMISSION.equals(message)) {
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, "\r\nDeinem APIKey fehlt die Berechtigung 'Allow Downloading'.\r\nAktiviere diese hier und versuche es erneut: ezfile.ch/?m=apidoc");
                } else {
                    throw new PluginException(LinkStatus.ERROR_FATAL, "\r\nYour APIKey is missing the permission 'Allow Downloading'.\r\nActivate it here and try again: ezfile.ch/?m=apidoc");
                }
            }
            final String finallink = getJson("download_ticket_url");
            if (finallink == null) {
                /* This should never happen. */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }

            int maxchunks = ACCOUNT_PREMIUM_MAXCHUNKS;
            if (link.getBooleanProperty(NOCHUNKS, false)) {
                maxchunks = 1;
            }

            dl = jd.plugins.BrowserAdapter.openDownload(br, link, finallink, ACCOUNT_PREMIUM_RESUME, maxchunks);
            if (dl.getConnection().getContentType().contains("html")) {
                handleServerErrors();
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
            }
            if (!this.dl.startDownload()) {
                try {
                    if (dl.externalDownloadStop()) {
                        return;
                    }
                } catch (final Throwable e) {
                }
                /* unknown error, we disable multiple chunks */
                if (link.getBooleanProperty(NOCHUNKS, false) == false) {
                    link.setProperty(NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        } else {
            doFree(link, true);
        }
    }

    private void accessAPI(final String url) throws IOException, PluginException {
        br.getPage(url);
        handleErrorsAPI();
    }

    private void handleServerErrors() throws PluginException {
        if (dl.getConnection().getResponseCode() == 403) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
        } else if (dl.getConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
        } else if (dl.getConnection().getResponseCode() == 503) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Too many connections or possible cloudflare failure", 10 * 60 * 1000l);
        }
    }

    private void handleErrorsAPI() throws PluginException {
        final String message = getJson("message");
        if (message != null) {
            if ("Try the reCaptcha challenge again, you have made a mistake.".equalsIgnoreCase(message)) {
                logger.warning("Extremely rare case: reCaptchaV2 response was not accepted --> Wrong user input or server issues");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            } else if ("Not enough direct download bandwidth in your account to download this file".equalsIgnoreCase(message)) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nAccount is out of traffic.\r\nAccount hat nicht genug Traffic.", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            } else if (message.equalsIgnoreCase("no akey parameter provided") || message.equalsIgnoreCase("unable to fetch apikey") || message.equalsIgnoreCase("Account disabled") || message.equalsIgnoreCase("no such user")) {
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen? Versuche folgendes:\r\n1. Falls dein Passwort Sonderzeichen enthält, ändere es (entferne diese) und versuche es erneut!\r\n2. Gib deine Zugangsdaten per Hand (ohne kopieren/einfügen) ein.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nYou're sure that the username and password you entered are correct? Some hints:\r\n1. If your password contains special characters, change it (remove them) and try again!\r\n2. Type in your username/password by hand without copy & paste.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
            } else if (message.equalsIgnoreCase("no such file")) {
                /* Should usually be covered by code executed before this */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (message.equalsIgnoreCase("private file")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "This is a private file which can only be downloaded by its owner");
            } else if (message.equalsIgnoreCase("The server this file is located on is under maintenance, try again later.")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 'The server this file is located on is under maintenance, try again later.'", 30 * 60 * 1000l);
            } else if (message.equalsIgnoreCase("Unable to retrieve server details, try again later.")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 'Unable to retrieve server details, try again later.'", 10 * 60 * 1000l);
            } else if (message.equalsIgnoreCase("Unable to issue download ticket.")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 'Unable to issue download ticket.'", 5 * 60 * 1000l);
            } else if (message.equalsIgnoreCase(API_ERROR_NO_PERMISSION)) {
                /* Don't do anything here - this is covered by additional errorhandling! */
                logger.info("Needed API permissions not given");
            } else {
                /*
                 * Other possible messages which should never happen: 'no fkey parameter provided', 'invalid fkey parameter size (should be
                 * at least 7 to 9 characters)',
                 */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                con = openConnection(br2, dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    private URLConnectionAdapter openConnection(final Browser br, final String directlink) throws IOException {
        URLConnectionAdapter con;
        if (isJDStable()) {
            con = br.openGetConnection(directlink);
        } else {
            con = br.openHeadConnection(directlink);
        }
        return con;
    }

    private boolean isJDStable() {
        return System.getProperty("jd.revision.jdownloaderrevision") == null;
    }

    /**
     * Wrapper<br/>
     * Tries to return value of key from JSon response, from default 'br' Browser.
     *
     * @author raztoki
     * */
    private String getJson(final String key) {
        return jd.plugins.hoster.K2SApi.JSonUtils.getJson(br.toString(), key);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return maxPrem.get();
    }

    public Browser prepBrowser(Browser br) {
        if (br == null) {
            br = new Browser();
        }
        br.getHeaders().put("User-Agent", useragent);
        br.getHeaders().put("Accept-Language", "de-de,de;q=0.8,en-us;q=0.5,en;q=0.3");
        br.setCookie("http://ezfile.ch/", "lang", "en");
        return br;
    }

    private String getFid(final DownloadLink downloadLink) {
        return new Regex(downloadLink.getDownloadURL(), "([a-z0-9]+)$").getMatch(0);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("free"))) {
            /* free accounts also have captchas */
            return true;
        }
        return false;
    }

    public static class EzFileChAccountFactory extends AccountFactory {

        public static class EzFileChPanel extends MigPanel implements EditAccountPanel {
            /**
             *
             */
            private static final long serialVersionUID = 1L;

            private final String      APIKEYHELP       = "Enter your APIKey / APIKey eingeben";

            private String getPassword() {
                if (this.pass == null) {
                    return null;
                }
                if (EMPTYPW.equals(new String(this.pass.getPassword()))) {
                    return null;
                }
                return new String(this.pass.getPassword());
            }

            ExtPasswordField          pass;

            private volatile Notifier notifier = null;
            private static String     EMPTYPW  = "                 ";

            public EzFileChPanel() {
                super("ins 0, wrap 2", "[][grow,fill]", "");
                add(new JLabel("Instructions / Anleitung:"));
                add(new JLink("https://ezfile.ch/?m=help&a=jdownloader"));

                add(new JLabel("APIKey:"));
                add(this.pass = new ExtPasswordField() {

                    @Override
                    public void onChanged() {
                        if (notifier != null) {
                            notifier.onNotify();
                        }
                    }

                }, "");
                pass.setHelpText(APIKEYHELP);
            }

            @Override
            public JComponent getComponent() {
                return this;
            }

            @Override
            public void setAccount(Account defaultAccount) {
                if (defaultAccount != null) {
                    pass.setText(defaultAccount.getPass());
                }
            }

            @Override
            public boolean validateInputs() {
                return getPassword() != null;
            }

            @Override
            public void setNotifyCallBack(Notifier notifier) {
                this.notifier = notifier;
            }

            @Override
            public Account getAccount() {
                return new Account(null, getPassword());
            }
        }

        @Override
        public EditAccountPanel getPanel() {
            return new EzFileChPanel();
        }

    }
}