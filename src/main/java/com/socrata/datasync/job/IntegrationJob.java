package com.socrata.datasync.job;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.socrata.datasync.Utils;
import com.socrata.datasync.config.controlfile.ControlFile;
import com.socrata.datasync.publishers.DeltaImporter2Publisher;
import com.socrata.datasync.publishers.FTPDropbox2Publisher;
import com.socrata.datasync.publishers.Soda2Publisher;
import org.apache.commons.cli.CommandLine;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;

import com.socrata.datasync.DataSyncMetadata;
import com.socrata.datasync.PublishMethod;
import com.socrata.datasync.SMTPMailer;
import com.socrata.datasync.SocrataConnectionInfo;
import com.socrata.datasync.config.CommandLineOptions;
import com.socrata.api.Soda2Producer;
import com.socrata.api.SodaImporter;
import com.socrata.datasync.config.userpreferences.UserPreferences;
import com.socrata.datasync.config.userpreferences.UserPreferencesJava;
import com.socrata.exceptions.SodaError;
import com.socrata.model.UpsertError;
import com.socrata.model.UpsertResult;
import com.socrata.model.importer.Column;
import com.socrata.model.importer.Dataset;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonSerialize(include= JsonSerialize.Inclusion.NON_NULL)
public class IntegrationJob extends Job {

    /**
	 * Stores a single integration job that can be opened/run in the GUI
	 * or in command-line mode.
	 */
    private UserPreferences userPrefs;

    // to upload entire file as a single chunk (numRowsPerChunk == 0)
    private static final int UPLOAD_SINGLE_CHUNK = 0;

    public static final int NUM_BYTES_PER_MB = 1048576;
    public static final List<String> allowedFileToPublishExtensions = Arrays.asList("csv", "tsv");
    public static final List<String> allowedControlFileExtensions = Arrays.asList("json");

    // Anytime a @JsonProperty is added/removed/updated in this class add 1 to this value
    private static final long fileVersionUID = 4L;

	private String datasetID = "";
	private String fileToPublish = "";
	private PublishMethod publishMethod = PublishMethod.replace;
    private boolean fileToPublishHasHeaderRow = true;
	private String pathToControlFile = null;
    private String controlFileContent = null;
    private boolean publishViaFTP = false;
    private boolean publishViaDi2Http = false;
    private ControlFile controlFile = null;

    public IntegrationJob() {
        userPrefs = new UserPreferencesJava();
	}

    /*
     * This is a method that enables DataSync preferences to be established
     * directly when DataSync is used in "library mode" or "command-line mode"
     * (rather than being loaded from Preferences class)
     */
    public IntegrationJob(UserPreferences userPrefs) {
        this.userPrefs = userPrefs;
    }

	/**
	 * Loads integration job data from a file and
	 * uses the saved data to populate the fields
	 * of this object
	 */
	public IntegrationJob(String pathToFile) throws IOException {
        userPrefs = new UserPreferencesJava();
        // first try reading the 'current' format
        ObjectMapper mapper = new ObjectMapper();
        try {
            IntegrationJob loadedJob = mapper.readValue(new File(pathToFile), IntegrationJob.class);
            setDatasetID(loadedJob.getDatasetID());
            setFileToPublish(loadedJob.getFileToPublish());
            setPublishMethod(loadedJob.getPublishMethod());
            setFileToPublishHasHeaderRow(loadedJob.getFileToPublishHasHeaderRow());
            setPathToSavedFile(pathToFile);
            setPathToControlFile(loadedJob.getPathToControlFile());
            setControlFileContent(loadedJob.getControlFileContent());
            setPublishViaFTP(loadedJob.getPublishViaFTP());
            setPublishViaDi2Http(loadedJob.getPublishViaDi2Http());
        } catch (IOException e) {
            // if reading new format fails...try reading old format into this object
            loadOldSijFile(pathToFile);
        }
	}

    @JsonProperty("fileVersionUID")
    public long getFileVersionUID() {
        return fileVersionUID;
    }

    @JsonProperty("datasetID")
    public void setDatasetID(String newDatasetID) {
        datasetID = newDatasetID;
    }

    @JsonProperty("datasetID")
    public String getDatasetID() {
        return datasetID;
    }

    @JsonProperty("fileToPublish")
    public void setFileToPublish(String newFileToPublish) {
        fileToPublish = newFileToPublish;
    }

    @JsonProperty("fileToPublish")
    public String getFileToPublish() {
        return fileToPublish;
    }

    @JsonProperty("publishMethod")
    public void setPublishMethod(PublishMethod newPublishMethod) {
        publishMethod = newPublishMethod;
    }

    @JsonProperty("publishMethod")
    public PublishMethod getPublishMethod() {
        return publishMethod;
    }

    @JsonProperty("fileToPublishHasHeaderRow")
    public boolean getFileToPublishHasHeaderRow() {
        return fileToPublishHasHeaderRow;
    }

    @JsonProperty("fileToPublishHasHeaderRow")
    public void setFileToPublishHasHeaderRow(boolean newFileToPublishHasHeaderRow) {
        fileToPublishHasHeaderRow = newFileToPublishHasHeaderRow;
    }

    @JsonProperty("pathToControlFile")
    public String getPathToControlFile() { return pathToControlFile; }

    @JsonProperty("pathToControlFile")
    public void setPathToControlFile(String newPathToControlFile) {
        pathToControlFile = newPathToControlFile;
    }

    @JsonProperty("pathToFTPControlFile")
    public String getFTPPathToControlFile() { return pathToControlFile; }

    @JsonProperty("pathToFTPControlFile")
    public void setPathToFTPControlFile(String newPathToControlFile) {
        pathToControlFile = newPathToControlFile;
    }

    @JsonProperty("controlFileContent")
    public String getControlFileContent() { return controlFileContent; }

    @JsonProperty("controlFileContent")
    public void setControlFileContent(String newcontrolFileContent) {
        controlFileContent = newcontrolFileContent;
    }

    @JsonProperty("ftpControlFileContent")
    public String getFTPControlFileContent() { return controlFileContent; }

    @JsonProperty("ftpControlFileContent")
    public void setFTPControlFileContent(String newcontrolFileContent) {
        controlFileContent = newcontrolFileContent;
    }

    @JsonProperty("publishViaFTP")
    public boolean getPublishViaFTP() { return publishViaFTP; }

    @JsonProperty("publishViaFTP")
    public void setPublishViaFTP(boolean newPublishViaFTP) { publishViaFTP = newPublishViaFTP; }

    @JsonProperty("publishViaDi2Http")
    public boolean getPublishViaDi2Http() { return publishViaDi2Http; }

    @JsonProperty("publishViaDi2Http")
    public void setPublishViaDi2Http(boolean newPublishViaDi2Http) { publishViaDi2Http = newPublishViaDi2Http; }



    public boolean validateArgs(CommandLine cmd) {
        return  validateDatasetIdArg(cmd) &&
                validateFileToPublishArg(cmd) &&
                validatePublishMethodArg(cmd) &&
                validateHeaderRowArg(cmd) &&
                validatePublishViaFtpArg(cmd) &&
                validatePublishViaDi2HttpArg(cmd) &&
                validatePathToControlFileArg(cmd);
    }

    public void configure(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        // Set required parameters
        setDatasetID(cmd.getOptionValue(options.DATASET_ID_FLAG));
        setFileToPublish(cmd.getOptionValue(options.FILE_TO_PUBLISH_FLAG));
        setPublishMethod(PublishMethod.valueOf(cmd.getOptionValue(options.PUBLISH_METHOD_FLAG)));
        if(cmd.getOptionValue(options.HAS_HEADER_ROW_FLAG).equalsIgnoreCase("true")) {
            setFileToPublishHasHeaderRow(true);
        } else { // cmd.getOptionValue(HAS_HEADER_ROW_FLAG) == "false"
            setFileToPublishHasHeaderRow(false);
        }

        // Set optional parameters
        if(cmd.getOptionValue(options.PATH_TO_CONTROL_FILE_FLAG) != null) {
            setPathToControlFile(cmd.getOptionValue(options.PATH_TO_CONTROL_FILE_FLAG));
            ObjectMapper mapper = new ObjectMapper();
            try {
                controlFile = mapper.readValue(pathToControlFile, ControlFile.class);
            } catch (Exception e1) {
                System.out.println("Could not properly parse control file:" + e1.getMessage());
                try {
                    System.out.println("Attempting to build control file from content in job file");
                    controlFile = mapper.readValue(controlFileContent, ControlFile.class);
                } catch (Exception e2) {
                    System.out.println("Resorting to default config file");
                    controlFile = ControlFile.generateControlFile(fileToPublish, publishMethod, null, true);
                }
            }
        }
        String publishViaFTP = cmd.getOptionValue(options.PUBLISH_VIA_FTP_FLAG, options.DEFAULT_PUBLISH_VIA_FTP);
        if(publishViaFTP.equalsIgnoreCase("true")) {
            setPublishViaFTP(true);
        } else { // cmd.getOptionValue("pf") == "false"
            setPublishViaFTP(false);
        }
        String publishViaDi2 = cmd.getOptionValue(options.PUBLISH_VIA_DI2_FLAG, options.DEFAULT_PUBLISH_VIA_DI2);
        if(publishViaDi2.equalsIgnoreCase("true")) {
            setPublishViaDi2Http(true);
        } else {
            setPublishViaDi2Http(false);
        }
    }

    /**
	 * 
	 * @return an error JobStatus if any input is invalid, otherwise JobStatus.VALID
	 */
	public JobStatus validate(SocrataConnectionInfo connectionInfo) {
        if(connectionInfo.getUrl().equals("")
				|| connectionInfo.getUrl().equals("https://")) {
			return JobStatus.INVALID_DOMAIN;
		}
        if(!Utils.uidIsValid(datasetID)) {
			return JobStatus.INVALID_DATASET_ID;
		}
		if(fileToPublish.equals("")) {
			return JobStatus.MISSING_FILE_TO_PUBLISH;
		} else {
			File checkFileToPublish = new File(fileToPublish);
			if(!checkFileToPublish.exists() || checkFileToPublish.isDirectory()) {
				JobStatus errorStatus = JobStatus.FILE_TO_PUBLISH_DOESNT_EXIST;
				errorStatus.setMessage(fileToPublish + ": File to publish does not exist");
				return errorStatus;
			}
            // Ensure file extension is an accepted format
            String fileToPublishExtension = Utils.getFileExtension(fileToPublish);
            if(!allowedFileToPublishExtensions.contains(fileToPublishExtension)) {
                return JobStatus.FILE_TO_PUBLISH_INVALID_FORMAT;
            }
		}
        if(publishMethod.equals(PublishMethod.append)) {
            // get row identifier of dataset
            final SodaImporter importer = SodaImporter.newImporter(connectionInfo.getUrl(), connectionInfo.getUser(), connectionInfo.getPassword(), connectionInfo.getToken());
            Dataset info = null;
            try {
                info = (Dataset) importer.loadDatasetInfo(datasetID);
                Column rowIdentifier = info.lookupRowIdentifierColumn();
                if (rowIdentifier != null) {
                    JobStatus status = JobStatus.INVALID_PUBLISH_METHOD;
                    status.setMessage("Append can only be performed on a dataset without a Row Identifier set" +
                            ". Dataset with ID '" + datasetID + "' has '" + rowIdentifier + "' set as the Row " +
                            "Identifier. You probably want to use the upsert method instead." );
                }
            } catch (Exception e) {
                // do nothing; if an exception occurs here it will almost
                // certainly propagate later where the error message will be more appropriate
            }
		}
        if(publishViaFTP || publishViaDi2Http) {
            if((pathToControlFile == null || pathToControlFile.equals("")) &&
                    (controlFileContent == null || controlFileContent.equals(""))) {
                JobStatus errorStatus = JobStatus.PUBLISH_ERROR;
                errorStatus.setMessage("You must generate or select a Control file if publishing via FTP SmartUpdate or delta-importer-2 over HTTP");
                return errorStatus;
            } else {
                if(pathToControlFile != null && !pathToControlFile.equals("")) {
                    File controlFile = new File(pathToControlFile);
                    if(!controlFile.exists() || controlFile.isDirectory()) {
                        JobStatus errorStatus = JobStatus.PUBLISH_ERROR;
                        errorStatus.setMessage(pathToControlFile + ": Control file does not exist");
                        return errorStatus;
                    }
                }
            }
        }
		
		// TODO add more validation? (e.g. validation of File To Publish header row)

        // Check if version is up-to-date (ONLY if running headlessly)
        try {
            Map<String, String> dataSyncVersionMetadata = DataSyncMetadata.getDataSyncMetadata();
            String newVersionDownloadMessage = "Download the new version (" +
                    DataSyncMetadata.getCurrentVersion(dataSyncVersionMetadata) + ") here:\n" +
                    DataSyncMetadata.getCurrentVersionDownloadUrl(dataSyncVersionMetadata) + "\n";

            if(!DataSyncMetadata.isLatestMajorVersion(dataSyncVersionMetadata)) {
                // Fail job if major version out-of-date
                JobStatus versionOutOfDate = JobStatus.VERSION_OUT_OF_DATE;
                versionOutOfDate.setMessage("DataSync critical update required: job cannot be run until " +
                        " DataSync is updated. " + newVersionDownloadMessage);
                return versionOutOfDate;
            } else if(!DataSyncMetadata.isLatestVersion(dataSyncVersionMetadata)) {
                // Warn user if version out of date at all
                System.err.println("\nWARNING: DataSync is out-of-date. " + newVersionDownloadMessage + "\n");
            }
        } catch (Exception e) {
            // do nothing upon failure
            System.out.println("WARNING: checking DataSync version failed.");
        }

        return JobStatus.VALID;
	}
	
	public JobStatus run() throws IOException {
		SocrataConnectionInfo connectionInfo = userPrefs.getConnectionInfo();
		
		UpsertResult result = null;
		JobStatus runStatus = JobStatus.SUCCESS;
        String runErrorMessage = null;

		JobStatus validationStatus = validate(connectionInfo);
        if(validationStatus.isError()) {
			runStatus = validationStatus;
            runErrorMessage = validationStatus.getMessage();
		} else if(publishViaFTP && !publishMethod.equals(PublishMethod.replace)) {
            runErrorMessage = "FTP does not currently support upsert, append or delete";
        } else {
            File fileToPublishFile = new File(fileToPublish);
            // attach a requestId to all Producer API calls (for error tracking purposes)
            String jobRequestId = Utils.generateRequestId();
            final Soda2Producer producer = Soda2Producer.newProducerWithRequestId(
                    connectionInfo.getUrl(), connectionInfo.getUser(), connectionInfo.getPassword(), connectionInfo.getToken(), jobRequestId);
            final SodaImporter importer = SodaImporter.newImporter(connectionInfo.getUrl(), connectionInfo.getUser(), connectionInfo.getPassword(), connectionInfo.getToken());
            final DeltaImporter2Publisher publisher = new DeltaImporter2Publisher(userPrefs);
            int filesizeChunkingCutoffBytes =
                    Integer.parseInt(userPrefs.getFilesizeChunkingCutoffMB()) * NUM_BYTES_PER_MB;
            int numRowsPerChunk = Integer.parseInt(userPrefs.getNumRowsPerChunk());

            boolean noPublishExceptions = false;
			try {
                 switch (publishMethod) {
                     case upsert:
                     case append:
                         result = doAppendOrUpsertViaHTTP(
                                 producer, importer, fileToPublishFile, filesizeChunkingCutoffBytes, numRowsPerChunk);
                         noPublishExceptions = true;
                         break;
                     case replace:
                         if (publishViaFTP) {
                             JobStatus ftpSmartUpdateStatus = doPublishViaFTPv2(importer, fileToPublishFile);
                             if (ftpSmartUpdateStatus.isError()) {
                                 runErrorMessage = ftpSmartUpdateStatus.getMessage();
                             } else {
                                 noPublishExceptions = true;
                             }
                         } else if (publishViaDi2Http) {
                             JobStatus di2Status = publisher.publishWithDi2OverHttp(datasetID, fileToPublishFile, controlFile);
                             if (di2Status.isError()) {
                                 runErrorMessage = di2Status.getMessage();
                             } else {
                                 noPublishExceptions = true;
                             }
                         } else {   // Publish via old HTTP method
                             result = Soda2Publisher.replaceNew(
                                     producer, importer, datasetID, fileToPublishFile, fileToPublishHasHeaderRow);
                             noPublishExceptions = true;
                         }
                         break;
                     case delete:
                         result = doDeleteViaHTTP(
                                 producer, importer, fileToPublishFile, filesizeChunkingCutoffBytes, numRowsPerChunk);
                         noPublishExceptions = true;
                         break;
                     default:
                         runErrorMessage = JobStatus.INVALID_PUBLISH_METHOD.getMessage();
                 }
			}
			catch (IOException ioException) {
                ioException.printStackTrace();
				runErrorMessage = "IO exception: " + ioException.getMessage();
			} 
			catch (SodaError sodaError) {
                sodaError.printStackTrace();
                runErrorMessage = sodaError.getMessage();
			} 
			catch (InterruptedException intrruptException) {
                intrruptException.printStackTrace();
				runErrorMessage = "Interrupted exception: " + intrruptException.getMessage();
			}
			catch (Exception other) {
                other.printStackTrace();
				runErrorMessage = "Unexpected exception: " + other.getMessage();
			}
			finally {
                if(noPublishExceptions) {
                    // Check for [row-level] SODA 2 errors
					if(result != null) {
                        if(result.errorCount() > 0) {
                            int lineIndexOffset = (fileToPublishHasHeaderRow) ? 2 : 1;
                            runErrorMessage = "";
							for (UpsertError upsertErr : result.getErrors()) {
								runErrorMessage += upsertErr.getError() + " (line "
										+ (upsertErr.getIndex() + lineIndexOffset) + " of file) \n";
							}
							runStatus = JobStatus.PUBLISH_ERROR;
						}
					}
				} else {
                    runStatus = JobStatus.PUBLISH_ERROR;
                    if(runErrorMessage != null) {
                        if(runErrorMessage.equals("Not found")) {
                            runErrorMessage = "Dataset with that ID does not exist or you do not have permission to publish to it";
                        }
                    }
				}
                producer.close();
                publisher.close();
			}
		}

        // TODO NEED to make this cleaner by turning JobStatus into regular class (rather than enum)
        if(runErrorMessage != null)
            runStatus.setMessage(runErrorMessage);

		String adminEmail = userPrefs.getAdminEmail();
		String logDatasetID = userPrefs.getLogDatasetID();
        String logPublishingErrorMessage = null;
        if(logDatasetID != null && !logDatasetID.equals("")) {
            String logDatasetUrl = userPrefs.getDomain() + "/d/" + userPrefs.getLogDatasetID();
            System.out.println("Publishing results to logging dataset (" + logDatasetUrl + ")...");
            logPublishingErrorMessage = addLogEntry(
                    logDatasetID, connectionInfo, this, runStatus, result);
            if(logPublishingErrorMessage != null) {
                System.out.println("Error publishing results to logging dataset (" + logDatasetUrl + "): " +
                        logPublishingErrorMessage);
            }
		}

		if(userPrefs.emailUponError() && !adminEmail.equals("")) {
            sendErrorNotificationEmail(
                    adminEmail, connectionInfo, runStatus, runErrorMessage, logDatasetID, logPublishingErrorMessage);
		}

        if(runStatus.isError()) {
            System.err.println("Job completed with errors: " + runStatus.getMessage());
        } else {
            System.out.println("Job completed successfully");
        }

        return runStatus;
	}

    /**
     * Adds an entry to specified log dataset with given job run information
     *
     * @return null if log entry was added successfully, otherwise return an error message as a String
     */
    public static String addLogEntry(final String logDatasetID, final SocrataConnectionInfo connectionInfo,
                                     final IntegrationJob job, final JobStatus status, final UpsertResult result) {
        final Soda2Producer producer = Soda2Producer.newProducer(connectionInfo.getUrl(), connectionInfo.getUser(), connectionInfo.getPassword(), connectionInfo.getToken());

        List<Map<String, Object>> upsertObjects = new ArrayList<Map<String, Object>>();
        Map<String, Object> newCols = new HashMap<String,Object>();

        // add standard log data
        Date currentDateTime = new Date();
        newCols.put("Date", (Object) currentDateTime);
        newCols.put("DatasetID", (Object) job.getDatasetID());
        newCols.put("FileToPublish", (Object) job.getFileToPublish());
        newCols.put("PublishMethod", (Object) job.getPublishMethod());
        newCols.put("JobFile", (Object) job.getPathToSavedFile());
        if(result != null) {
            newCols.put("RowsUpdated", (Object) result.rowsUpdated);
            newCols.put("RowsCreated", (Object) result.rowsCreated);
            newCols.put("RowsDeleted", (Object) result.rowsDeleted);
        }
        if(status.isError()) {
            newCols.put("Errors", (Object) status.getMessage());
        } else {
            newCols.put("Success", (Object) true);
        }
        newCols.put("DataSyncVersion", (Object) DataSyncMetadata.getDatasyncVersion());
        upsertObjects.add(ImmutableMap.copyOf(newCols));

        System.out.println("Adding row to logging dataset (" + connectionInfo.getUrl() + "/d/" + logDatasetID + ")...");
        String logPublishingErrorMessage = null;
        try {
            producer.upsert(logDatasetID, upsertObjects);
        }
        catch (SodaError sodaError) {
            sodaError.printStackTrace();
            logPublishingErrorMessage = sodaError.getMessage();
        }
        catch (InterruptedException intrruptException) {
            intrruptException.printStackTrace();
            logPublishingErrorMessage = intrruptException.getMessage();
        }
        catch (Exception other) {
            other.printStackTrace();
            logPublishingErrorMessage = other.toString() + ": " + other.getMessage();
        }
        return logPublishingErrorMessage;
    }

    private JobStatus doPublishViaFTPv2(SodaImporter importer, File fileToPublishFile) {
        if((pathToControlFile != null && !pathToControlFile.equals(""))) {
            return FTPDropbox2Publisher.publishViaFTPDropboxV2(
                    userPrefs, importer,
                    datasetID, fileToPublishFile, new File(pathToControlFile));
        } else {
            return FTPDropbox2Publisher.publishViaFTPDropboxV2(
                    userPrefs, importer,
                    datasetID, fileToPublishFile, controlFileContent);
        }
    }

    private void sendErrorNotificationEmail(final String adminEmail, final SocrataConnectionInfo connectionInfo, final JobStatus runStatus, final String runErrorMessage, final String logDatasetID, final String logPublishingErrorMessage) {
        String errorEmailMessage = "";
        String urlToLogDataset = connectionInfo.getUrl() + "/d/" + logDatasetID;
        if(runStatus.isError()) {
            errorEmailMessage += "There was an error updating a dataset.\n"
                    + "\nDataset: " + connectionInfo.getUrl() + "/d/" + getDatasetID()
                    + "\nFile to publish: " + fileToPublish
                    + "\nFile to publish has header row: " + fileToPublishHasHeaderRow
                    + "\nPublish method: " + publishMethod
                    + "\nJob File: " + pathToSavedJobFile
                    + "\nError message: " + runErrorMessage
                    + "\nLog dataset: " + urlToLogDataset + "\n\n";
        }
        if(logPublishingErrorMessage != null) {
            errorEmailMessage += "There was an error updating the log dataset: "
                    + urlToLogDataset + "\n"
                    + "Error message: " + logPublishingErrorMessage + "\n\n";
        }
        if(runStatus.isError() || logPublishingErrorMessage != null) {
            try {
                SMTPMailer.send(adminEmail, "Socrata DataSync Error", errorEmailMessage);
            } catch (Exception e) {
                System.out.println("Error sending email to: " + adminEmail + "\n" + e.getMessage());
            }
        }
    }

    private UpsertResult doAppendOrUpsertViaHTTP(Soda2Producer producer, SodaImporter importer, File fileToPublishFile, int filesizeChunkingCutoffBytes, int numRowsPerChunk) throws SodaError, InterruptedException, IOException {
        int numberOfRows = numRowsPerChunk(fileToPublishFile, filesizeChunkingCutoffBytes, numRowsPerChunk);
        UpsertResult result = Soda2Publisher.appendUpsert(
                producer, importer, datasetID, fileToPublishFile, numberOfRows, fileToPublishHasHeaderRow);
        return result;
    }

    private UpsertResult doDeleteViaHTTP(
            Soda2Producer producer, SodaImporter importer, File fileToPublishFile, int filesizeChunkingCutoffBytes, int numRowsPerChunk)
            throws SodaError, InterruptedException, IOException {
        int numberOfRows = numRowsPerChunk(fileToPublishFile, filesizeChunkingCutoffBytes, numRowsPerChunk);
        UpsertResult result = Soda2Publisher.deleteRows(
                producer, importer, datasetID, fileToPublishFile, numberOfRows, fileToPublishHasHeaderRow);
        return result;
    }

    private int numRowsPerChunk(File fileToPublishFile, int filesizeChunkingCutoffBytes, int numRowsPerChunk) {
        int numberOfRows;
        if(fileToPublishFile.length() > filesizeChunkingCutoffBytes) {
            numberOfRows = numRowsPerChunk;
        } else {
            numberOfRows = UPLOAD_SINGLE_CHUNK;
        }
        return numberOfRows;
    }

    private boolean validatePathToControlFileArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        if(cmd.getOptionValue(options.PATH_TO_CONTROL_FILE_FLAG) != null) {
            String publishingWithFtp = cmd.getOptionValue(options.PUBLISH_VIA_FTP_FLAG);
            String publishingWithDi2 = cmd.getOptionValue(options.PUBLISH_VIA_DI2_FLAG);
            if ((publishingWithFtp == null || publishingWithFtp.equalsIgnoreCase("false")) &&
                (publishingWithDi2 == null || publishingWithDi2.equalsIgnoreCase("false"))) {
                System.err.println("Invalid argument: -sc,--" + options.PATH_TO_CONTROL_FILE_FLAG + " cannot be supplied " +
                        "unless -pf,--" + options.PUBLISH_VIA_FTP_FLAG + " is 'true' or " +
                        "unless -di2,--" + options.PUBLISH_VIA_DI2_FLAG + " is 'true'");
                return false;
            }
        }

        if(cmd.getOptionValue(options.PATH_TO_CONTROL_FILE_FLAG) != null) {
            // TODO remove this when flags override other parameters
            if(cmd.getOptionValue(options.PUBLISH_METHOD_FLAG) != null) {
                System.out.println("WARNING: -m,--" + options.PUBLISH_METHOD_FLAG + " is being ignored because " +
                        "-sc,--" + options.PATH_TO_CONTROL_FILE_FLAG + " is supplied");
            }
            if(cmd.getOptionValue(options.HAS_HEADER_ROW_FLAG) != null) {
                System.out.println("WARNING: -h,--" + options.HAS_HEADER_ROW_FLAG + " is being ignored because " +
                        "-sc,--" + options.PATH_TO_CONTROL_FILE_FLAG + " is supplied");
            }
        }
        return true;
    }

    private boolean validatePublishViaFtpArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        String publishingWithFtp = cmd.getOptionValue(options.PUBLISH_VIA_FTP_FLAG);
        if(publishingWithFtp != null &&
                !publishingWithFtp.equalsIgnoreCase("true") &&
                !publishingWithFtp.equalsIgnoreCase("false")) {
            System.err.println("Invalid argument: -pf,--" + options.PUBLISH_VIA_FTP_FLAG + " must be 'true' or 'false'");
            return false;
        }
        return true;
    }

    private boolean validatePublishViaDi2HttpArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        String publishingWithDi2 = cmd.getOptionValue(options.PUBLISH_VIA_DI2_FLAG);
        if (publishingWithDi2 == null) {
            return true;
        }
        if(!publishingWithDi2.equalsIgnoreCase("true") && !publishingWithDi2.equalsIgnoreCase("false")) {
            System.err.println("Invalid argument: -pf,--" + options.PUBLISH_VIA_DI2_FLAG + " must be 'true' or 'false'");
            return false;
        }
        if (publishingWithDi2.equalsIgnoreCase("true")) {
            String publishingWithFtp = cmd.getOptionValue(options.PUBLISH_VIA_FTP_FLAG);
            if (publishingWithFtp != null && publishingWithFtp.equalsIgnoreCase("true")) {
                System.err.println("Only one of -pf,--" + options.PUBLISH_VIA_DI2_FLAG + " and " +
                        "-di2,--" + options.PUBLISH_VIA_DI2_FLAG + " may be set to 'true'");
                return false;
            }
            if (PublishMethod.valueOf(cmd.getOptionValue(options.PUBLISH_METHOD_FLAG)) != PublishMethod.replace) {
                System.err.println("Invalid argument: -pf,--" + options.PUBLISH_VIA_DI2_FLAG + " must be " +
                        PublishMethod.replace.name() + " when " +
                        "-di2,--" + options.PUBLISH_VIA_DI2_FLAG + " is set to 'true'");
                return false;
            }
            if (cmd.getOptionValue(options.PATH_TO_CONTROL_FILE_FLAG) == null) {
                System.err.println("A control file must be specified when " +
                        "-di2,--" + options.PUBLISH_VIA_DI2_FLAG + " is set to 'true'");
                return false;
            }
        }
        return true;
    }

    private boolean validateHeaderRowArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        if(cmd.getOptionValue(options.HAS_HEADER_ROW_FLAG) == null) {
            System.err.println("Missing required argument: -h,--" + options.HAS_HEADER_ROW_FLAG + " is required");
            return false;
        }
        if(!cmd.getOptionValue(options.HAS_HEADER_ROW_FLAG).equalsIgnoreCase("true")
                && !cmd.getOptionValue(options.HAS_HEADER_ROW_FLAG).equalsIgnoreCase("false")) {
            System.err.println("Invalid argument: -h,--" + options.HAS_HEADER_ROW_FLAG + " must be 'true' or 'false'");
            return false;
        }
        // TODO: after talk to robert, figure out what the restrictions are for di2/http
        return true;
    }

    private boolean validateFileToPublishArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        if(cmd.getOptionValue(options.FILE_TO_PUBLISH_FLAG) == null) {
            System.err.println("Missing required argument: -f,--" + options.FILE_TO_PUBLISH_FLAG + " is required");
            return false;
        }
        return true;
    }

    private boolean validateDatasetIdArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        if(cmd.getOptionValue(options.DATASET_ID_FLAG) == null) {
            System.err.println("Missing required argument: -i,--" + options.DATASET_ID_FLAG + " is required");
            return false;
        }
        return true;
    }

    private boolean validatePublishMethodArg(CommandLine cmd) {
        CommandLineOptions options = new CommandLineOptions();
        String method = cmd.getOptionValue("m");
        if(method == null) {
            System.err.println("Missing required argument: -m,--" + options.PUBLISH_METHOD_FLAG + " is required");
            return false;
        }
        boolean publishMethodValid = false;
        final String inputPublishMethod = method;
        for(PublishMethod m : PublishMethod.values()) {
            if(inputPublishMethod.equals(m.name()))
                publishMethodValid = true;
        }
        if(!publishMethodValid) {
            System.err.println("Invalid argument: -m,--" + options.PUBLISH_METHOD_FLAG + " must be " +
                    Arrays.toString(PublishMethod.values()));
            return false;
        }
        return true;
    }


    /**
     * This allows backward compatability with DataSync 0.1 .sij file format
     *
     * @param pathToFile .sij file that uses old serialization format (Java native)
     * @throws IOException
     */
    private void loadOldSijFile(String pathToFile) throws IOException {
        try {
            InputStream file = new FileInputStream(pathToFile);
            InputStream buffer = new BufferedInputStream(file);
            ObjectInput input = new ObjectInputStream (buffer);
            try{
                com.socrata.datasync.IntegrationJob loadedJobOld = (com.socrata.datasync.IntegrationJob) input.readObject();
                setDatasetID(loadedJobOld.getDatasetID());
                setFileToPublish(loadedJobOld.getFileToPublish());
                setPublishMethod(loadedJobOld.getPublishMethod());
                setPathToSavedFile(pathToFile);
                setFileToPublishHasHeaderRow(true);
                setPublishViaFTP(false);
                setPathToControlFile(null);
                setControlFileContent(null);
            }
            finally{
                input.close();
            }
        } catch(Exception e) {
            // TODO add log entry?
            throw new IOException(e.toString());
        }
    }

}