package org.agilos.jira.zendesk;

import com.atlassian.jira.ManagerFactory;
import com.atlassian.jira.issue.attachment.Attachment;
import com.atlassian.jira.util.AttachmentUtils;
import org.agilos.jira.zendesk.notifications.ChangeMessage;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.log4j.Logger;
import org.ofbiz.core.entity.GenericEntityException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.StringTokenizer;

public class AttachmentHandler {
    private static Logger log = Logger.getLogger(AttachmentHandler.class.getName());
    private static DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    public static boolean uploadAttachments = true;

    /**
     * Adds the relevant attachment change information to the change message and uploads the attachment to Zendesk (if needed).
     *
     * @param changeMessage The change message to add the attachment change information to
     * @param attachmentId  The attachment Id, needed to retrieve the attachment
     */
    public static void handleAttachment(ChangeMessage changeMessage, Long attachmentId) {

        Attachment attachment = ManagerFactory.getAttachmentManager().getAttachment(attachmentId);
        String attachmentLink = getAttachmentLink(attachment);

        changeMessage.addChange("Attachment", attachment.getFilename() + " " + attachmentLink, null);
        if (uploadAttachments) {
            if (attachment.getFilesize() > 7000000) {
                String message = "Not uploading attachment " + attachment.getFilename() + " to Zendesk, size " + attachment.getFilesize() + " bytes exceeds limit of 7 MB";
                log.info(message);
                changeMessage.addChangeComment(message);
            } else {
                try {
                    String uploadToken = uploadAttachment(attachmentId);
                    changeMessage.addUpload(uploadToken);
                } catch (Exception e) {
                    log.error("Failed to upload", e);
                    changeMessage.addChangeComment("Failed to upload attachment " + attachment.getFilename() + ", see JIRA log for details");
                }
            }
        }
    }

    private static String getAttachmentLink(Attachment attachment) {
        StringBuffer stringLinkBuffer = new StringBuffer(NotificationDispatcher.getBaseUrl() + "/secure/attachment/" + attachment.getId() + "/");
        stringLinkBuffer.append(convertToUrlString(attachment.getFilename()));
        return stringLinkBuffer.toString();
    }

    private static String convertToUrlString(String string) {
        StringTokenizer st = new StringTokenizer(string, " ");
        StringBuffer stringBuffer = new StringBuffer();

        while (st.hasMoreElements()) {
            stringBuffer.append(st.nextElement());
            if (st.hasMoreElements()) stringBuffer.append("+");
        }
        return stringBuffer.toString();
    }

    private static String uploadAttachment(Long attachmentId) throws IOException, SAXException, ParserConfigurationException, GenericEntityException {
        Attachment attachment = ManagerFactory.getAttachmentManager().getAttachment(attachmentId);

        HttpClient client = new HttpClient();
        client.getParams().setAuthenticationPreemptive(true);

        Credentials defaultcreds = new UsernamePasswordCredentials(
                ZendeskNotifier.getZendeskserverConfiguration().getUser(),
                ZendeskNotifier.getZendeskserverConfiguration().getPassword());
        client.getState().setCredentials(new AuthScope(
                ZendeskNotifier.getZendeskserverConfiguration().getUrl().getHost(),
                ZendeskNotifier.getZendeskserverConfiguration().getUrl().getPort(),
                AuthScope.ANY_REALM), defaultcreds);

        PostMethod postMethod = new PostMethod(ZendeskNotifier.getZendeskserverConfiguration().getUrl() + "/uploads.xml?filename=" + convertToUrlString(attachment.getFilename()));

        client.getHttpConnectionManager().getParams().setConnectionTimeout(5000);

        RequestEntity requestEntity = new InputStreamRequestEntity(new FileInputStream(AttachmentUtils.getAttachmentFile(attachment)));
        postMethod.setRequestEntity(requestEntity);
        postMethod.setRequestHeader("Content-type", "application/x-www-form-urlencoded");

        String attachmentUploadToken = null;

        if (client.executeMethod(postMethod) != HttpStatus.SC_OK) {
            log.info("Failed to upload attachment " + attachment.getFilename() + "to " + postMethod.getPath() + ", response was " + postMethod.getResponseBodyAsString());
        } else {
            log.info("Attachment " + attachment.getId() + " posted, response was " + postMethod.getResponseBodyAsString());
            Document document = factory.newDocumentBuilder().parse(postMethod.getResponseBodyAsStream());
            attachmentUploadToken = document.getFirstChild().getAttributes().getNamedItem("token").getNodeValue();
        }

        postMethod.releaseConnection();

        return attachmentUploadToken;
	}
}
