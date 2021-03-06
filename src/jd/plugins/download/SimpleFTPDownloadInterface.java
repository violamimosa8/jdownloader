package jd.plugins.download;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.rmi.ConnectException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import jd.controlling.downloadcontroller.DiskSpaceReservation;
import jd.controlling.downloadcontroller.ExceptionRunnable;
import jd.controlling.downloadcontroller.FileIsLockedException;
import jd.controlling.downloadcontroller.ManagedThrottledConnectionHandler;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.SimpleFTP;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginProgress;
import jd.plugins.download.raf.OldRAFDownload;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Application;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.net.throttledconnection.MeteredThrottledInputStream;
import org.appwork.utils.speedmeter.AverageSpeedMeter;
import org.jdownloader.plugins.DownloadPluginProgress;
import org.jdownloader.plugins.HashCheckPluginProgress;
import org.jdownloader.plugins.SkipReason;
import org.jdownloader.plugins.SkipReasonException;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.translate._JDT;

public class SimpleFTPDownloadInterface extends DownloadInterface {

    private final Downloadable                      downloadable;
    private final ManagedThrottledConnectionHandler connectionHandler;
    private final Logger                            logger;
    private final SimpleFTP                         simpleFTP;
    private String                                  filePath;
    private AtomicBoolean                           abort                    = new AtomicBoolean(false);
    private AtomicBoolean                           terminated               = new AtomicBoolean(false);
    private RandomAccessFile                        outputPartFileRaf;
    private File                                    outputCompleteFile;
    private File                                    outputFinalCompleteFile;
    private File                                    outputPartFile;
    protected PluginException                       caughtPluginException    = null;
    protected long                                  totalLinkBytesLoaded     = -1;
    protected AtomicLong                            totalLinkBytesLoadedLive = new AtomicLong(0);
    private long                                    startTimeStamp           = -1;
    private boolean                                 resumed;

    public SimpleFTPDownloadInterface(SimpleFTP simpleFTP, final DownloadLink link, String filePath) {
        connectionHandler = new ManagedThrottledConnectionHandler();
        downloadable = new DownloadLinkDownloadable(link) {
            @Override
            public boolean isResumable() {
                return link.getBooleanProperty("RESUME", true);
            }

            @Override
            public void setResumeable(boolean value) {
                link.setProperty("RESUME", value);
                super.setResumeable(value);
            }
        };
        this.filePath = filePath;
        logger = downloadable.getLogger();
        downloadable.setDownloadInterface(this);
        this.simpleFTP = simpleFTP;
    }

    @Override
    public ManagedThrottledConnectionHandler getManagedConnetionHandler() {
        return connectionHandler;
    }

    private void createOutputChannel() throws SkipReasonException {
        try {
            String fileOutput = downloadable.getFileOutput();
            logger.info("createOutputChannel for " + fileOutput);
            String finalFileOutput = downloadable.getFinalFileOutput();
            outputCompleteFile = new File(fileOutput);
            outputFinalCompleteFile = outputCompleteFile;
            if (!fileOutput.equals(finalFileOutput)) {
                outputFinalCompleteFile = new File(finalFileOutput);
            }
            outputPartFile = new File(downloadable.getFileOutputPart());
            try {
                if (Application.getJavaVersion() >= Application.JAVA17) {
                    SparseFile.createSparseFile(outputPartFile);
                }
            } catch (IOException e) {
            }
            outputPartFileRaf = new RandomAccessFile(outputPartFile, "rw");
        } catch (Exception e) {
            LogSource.exception(logger, e);
            throw new SkipReasonException(SkipReason.INVALID_DESTINATION, e);
        }
    }

    @Override
    public URLConnectionAdapter connect(Browser br) throws Exception {
        throw new WTFException("Not needed for SimpleFTPDownloadInterface");
    }

    @Override
    public long getTotalLinkBytesLoadedLive() {
        return totalLinkBytesLoadedLive.get();
    }

    protected void download(String filename, RandomAccessFile raf, boolean resume) throws IOException, PluginException {
        long resumePosition = 0;
        if (!simpleFTP.isBinary()) {
            logger.info("Warning: Download in ASCII mode may fail!");
        }
        InetSocketAddress pasv = simpleFTP.pasv();
        resumed = false;
        if (resume) {
            resumePosition = raf.length();
            if (resumePosition > 0) {
                resumed = true;
                totalLinkBytesLoadedLive.set(resumePosition);
                simpleFTP.sendLine("REST " + resumePosition);
                try {
                    simpleFTP.readLines(new int[] { 350 }, "Resume not supported");
                    downloadable.setResumeable(true);
                } catch (final IOException e) {
                    if (e.getMessage().contains("Resume not")) {
                        downloadable.setResumeable(false);
                        throw new PluginException(LinkStatus.ERROR_RETRY);
                    }
                    throw e;
                }
            }
        }
        MeteredThrottledInputStream input = null;
        Socket dataSocket = null;
        totalLinkBytesLoaded = -1;
        try {
            dataSocket = new Socket();
            dataSocket.setSoTimeout(30 * 1000);
            dataSocket.connect(new InetSocketAddress(pasv.getHostName(), pasv.getPort()), 30 * 1000);
            simpleFTP.sendLine("RETR " + filename);
            simpleFTP.readLines(new int[] { 150, 125 }, null);
            input = new MeteredThrottledInputStream(dataSocket.getInputStream(), new AverageSpeedMeter(10));
            connectionHandler.addThrottledConnection(input);
            if (resumePosition > 0) {
                /* in case we do resume, reposition the writepointer */
                raf.seek(resumePosition);
            }
            byte[] buffer = new byte[32767];
            int bytesRead = 0;
            totalLinkBytesLoaded = resumePosition;
            while ((bytesRead = input.read(buffer)) != -1) {
                if (abort.get()) {
                    break;
                }
                if (bytesRead > 0) {
                    totalLinkBytesLoaded += bytesRead;
                    raf.write(buffer, 0, bytesRead);
                    totalLinkBytesLoadedLive.addAndGet(bytesRead);
                }
            }
            /* max 10 seks wait for buggy servers */
            simpleFTP.getSocket().setSoTimeout(20 * 1000);
            simpleFTP.shutDownSocket(dataSocket);
            input.close();
            try {
                simpleFTP.readLine();
            } catch (SocketTimeoutException e) {
                LogSource.exception(logger, e);
            }
        } catch (SocketTimeoutException e) {
            LogSource.exception(logger, e);
            error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, _JDT._.download_error_message_networkreset(), LinkStatus.VALUE_NETWORK_IO_ERROR));
            simpleFTP.sendLine("ABOR");
            simpleFTP.readLine();
            return;
        } catch (SocketException e) {
            LogSource.exception(logger, e);
            error(new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, _JDT._.download_error_message_networkreset(), 1000l * 60 * 5));
            simpleFTP.sendLine("ABOR");
            simpleFTP.readLine();
            return;
        } catch (ConnectException e) {
            LogSource.exception(logger, e);
            error(new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, _JDT._.download_error_message_networkreset(), 1000l * 60 * 5));
            simpleFTP.sendLine("ABOR");
            simpleFTP.readLine();
            return;
        } finally {
            try {
                connectionHandler.removeThrottledConnection(input);
            } catch (final Throwable e) {
            }
            try {
                input.close();
            } catch (Throwable e) {
            }
            try {
                raf.close();
            } catch (Throwable e) {
            }
            if (totalLinkBytesLoaded >= 0) {
                downloadable.setDownloadBytesLoaded(totalLinkBytesLoaded);
            }
            simpleFTP.shutDownSocket(dataSocket);
        }
    }

    @Override
    public boolean startDownload() throws Exception {
        try {
            DownloadPluginProgress downloadPluginProgress = null;
            downloadable.setConnectionHandler(this.getManagedConnetionHandler());
            final DiskSpaceReservation reservation = downloadable.createDiskSpaceReservation();
            try {
                if (!downloadable.checkIfWeCanWrite(new ExceptionRunnable() {

                    @Override
                    public void run() throws Exception {
                        downloadable.checkAndReserve(reservation);
                        createOutputChannel();
                        try {
                            downloadable.lockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                        } catch (FileIsLockedException e) {
                            downloadable.unlockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                            throw new PluginException(LinkStatus.ERROR_ALREADYEXISTS);
                        }
                    }
                }, null)) {
                    throw new SkipReasonException(SkipReason.INVALID_DESTINATION);
                }
                startTimeStamp = System.currentTimeMillis();
                downloadPluginProgress = new DownloadPluginProgress(downloadable, this, Color.GREEN.darker());
                downloadable.addPluginProgress(downloadPluginProgress);
                downloadable.setAvailable(AvailableStatus.TRUE);
                download(filePath, outputPartFileRaf, downloadable.isResumable());
            } finally {
                try {
                    downloadable.free(reservation);
                } catch (final Throwable e) {
                    LogSource.exception(logger, e);
                }
                try {
                    downloadable.addDownloadTime(System.currentTimeMillis() - getStartTimeStamp());
                } catch (final Throwable e) {
                }
                downloadable.removePluginProgress(downloadPluginProgress);
            }
            HashResult result = onDownloadReady();
            if (result != null) {
                logger.info(result.getHashInfo().getType() + "-Check: " + (result.match() ? "ok" : "failed"));
                if (result.match()) {
                    downloadable.setLinkStatusText(_JDT._.system_download_doCRC2_success(result.getHashInfo().getType()));
                } else {
                    throw new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT._.system_download_doCRC2_failed(result.getHashInfo().getType()));
                }
            }
            return handleErrors();
        } finally {
            downloadable.unlockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
            cleanupDownladInterface();
        }
    }

    protected HashResult getHashResult(File file) throws InterruptedException {
        if (JsonConfig.create(GeneralSettings.class).isHashCheckEnabled() && downloadable.isHashCheckEnabled()) {
            AtomicBoolean hashCheckLock = new AtomicBoolean(false);
            synchronized (OldRAFDownload.HASHCHECK_QEUEU) {
                OldRAFDownload.HASHCHECK_QEUEU.add(hashCheckLock);
                hashCheckLock.set(OldRAFDownload.HASHCHECK_QEUEU.indexOf(hashCheckLock) != 0);
            }
            try {
                if (hashCheckLock.get()) {
                    synchronized (hashCheckLock) {
                        if (hashCheckLock.get()) {
                            final PluginProgress hashProgress = new HashCheckPluginProgress(null, Color.YELLOW.darker().darker(), null);
                            try {
                                downloadable.addPluginProgress(hashProgress);
                                hashCheckLock.wait();
                            } finally {
                                downloadable.removePluginProgress(hashProgress);
                            }
                        }
                    }
                }
                HashInfo hashInfo = downloadable.getHashInfo();
                HashResult hashResult = downloadable.getHashResult(hashInfo, file);
                if (hashResult != null) {
                    logger.info(hashResult.toString());
                }
                return hashResult;
            } finally {
                synchronized (OldRAFDownload.HASHCHECK_QEUEU) {
                    boolean callNext = OldRAFDownload.HASHCHECK_QEUEU.indexOf(hashCheckLock) == 0;
                    OldRAFDownload.HASHCHECK_QEUEU.remove(hashCheckLock);
                    if (OldRAFDownload.HASHCHECK_QEUEU.size() > 0 && callNext) {
                        hashCheckLock = OldRAFDownload.HASHCHECK_QEUEU.get(0);
                    } else {
                        hashCheckLock = null;
                    }
                }
                if (hashCheckLock != null) {
                    synchronized (hashCheckLock) {
                        hashCheckLock.set(false);
                        hashCheckLock.notifyAll();
                    }
                }
            }
        }
        return null;
    }

    protected HashResult onDownloadReady() throws Exception {
        HashResult result = null;
        cleanupDownladInterface();
        if (!handleErrors()) {
            return result;
        }
        HashResult hashResult = getHashResult(outputPartFile);
        downloadable.setHashResult(hashResult);
        if (hashResult == null || hashResult.match()) {
            downloadable.setVerifiedFileSize(outputPartFile.length());
        } else {
            if (hashResult.getHashInfo().isTrustworthy()) {
                throw new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT._.system_download_doCRC2_failed(hashResult.getHashInfo().getType()));
            }
        }
        boolean renameOkay = downloadable.rename(outputPartFile, outputCompleteFile);
        if (!renameOkay) {

            error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT._.system_download_errors_couldnotrename(), LinkStatus.VALUE_LOCAL_IO_ERROR));
        }
        return result;
    }

    /**
     * ueber error() kann ein fehler gemeldet werden. DIe Methode entscheided dann ob dieser fehler zu einem Abbruch fuehren muss
     */
    protected void error(PluginException pluginException) {
        synchronized (this) {
            /* if we recieved external stop, then we dont have to handle errors */
            if (externalDownloadStop()) {
                return;
            }
            LogSource.exception(logger, pluginException);
            if (caughtPluginException == null) {
                caughtPluginException = pluginException;
            }
        }
        terminate();
    }

    protected void cleanupDownladInterface() {
        try {
            downloadable.removeConnectionHandler(this.getManagedConnetionHandler());
        } catch (final Throwable e) {
        }
        try {
            this.simpleFTP.disconnect();
        } catch (Throwable e) {
        }
        closeOutputChannel();
    }

    private void closeOutputChannel() {
        try {
            RandomAccessFile loutputPartFileRaf = outputPartFileRaf;
            if (loutputPartFileRaf != null) {
                logger.info("Close File. Let AV programs run");
                loutputPartFileRaf.close();
            }
        } catch (Throwable e) {
            LogSource.exception(logger, e);
        } finally {
            outputPartFileRaf = null;
        }
    }

    protected long getFileSize() {
        return downloadable.getVerifiedFileSize();
    }

    public boolean handleErrors() throws PluginException {
        if (externalDownloadStop()) {
            return false;
        }
        if (getFileSize() > 0 && totalLinkBytesLoaded != getFileSize()) {
            if (totalLinkBytesLoaded > getFileSize()) {
                /*
                 * workaround for old bug deep in this downloadsystem. more data got loaded (maybe just counting bug) than filesize. but in
                 * most cases the file is okay! WONTFIX because new downloadsystem is on its way
                 */
                logger.severe("Filesize: " + getFileSize() + " Loaded: " + totalLinkBytesLoaded);
                if (caughtPluginException == null) {
                    downloadable.setLinkStatus(LinkStatus.FINISHED);

                }
                return true;
            }
            logger.severe("Filesize: " + getFileSize() + " Loaded: " + totalLinkBytesLoaded);
            logger.severe("DOWNLOAD INCOMPLETE DUE TO FILESIZECHECK");
            if (caughtPluginException != null) {
                throw caughtPluginException;
            }
            throw new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, _JDT._.download_error_message_incomplete());
        }
        if (caughtPluginException == null) {
            downloadable.setLinkStatus(LinkStatus.FINISHED);
            downloadable.setVerifiedFileSize(outputCompleteFile.length());
            return true;
        } else {
            throw caughtPluginException;
        }
    }

    @Override
    public URLConnectionAdapter getConnection() {
        throw new WTFException("Not needed for SimpleFTPDownloadInterface");
    }

    @Override
    public void stopDownload() {
        if (abort.getAndSet(true) == false) {
            logger.info("externalStop recieved");
            terminate();
        }
    }

    protected void terminate() {
        if (terminated.getAndSet(true) == false) {
            if (!externalDownloadStop()) {
                logger.severe("A critical Downloaderror occured. Terminate...");
            }
        }
    }

    @Override
    public boolean externalDownloadStop() {
        return abort.get();
    }

    @Override
    public long getStartTimeStamp() {
        return startTimeStamp;
    }

    @Override
    public void close() {
    }

    @Override
    public Downloadable getDownloadable() {
        return downloadable;
    }

    @Override
    public boolean isResumedDownload() {
        return resumed;
    }

}
