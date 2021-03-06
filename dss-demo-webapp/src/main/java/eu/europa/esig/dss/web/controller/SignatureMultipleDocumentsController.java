package eu.europa.esig.dss.web.controller;

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.xml.bind.DatatypeConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.SessionAttributes;

import eu.europa.esig.dss.enumerations.ASiCContainerType;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.EncryptionAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureForm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.MimeType;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.web.WebAppUtils;
import eu.europa.esig.dss.web.editor.EnumPropertyEditor;
import eu.europa.esig.dss.web.model.DataToSignParams;
import eu.europa.esig.dss.web.model.GetDataToSignResponse;
import eu.europa.esig.dss.web.model.SignDocumentResponse;
import eu.europa.esig.dss.web.model.SignatureMultipleDocumentsForm;
import eu.europa.esig.dss.web.model.SignatureValueAsString;
import eu.europa.esig.dss.web.service.SigningService;

@Controller
@SessionAttributes(value = { "signatureMultipleDocumentsForm", "signedDocument" })
@RequestMapping(value = "/sign-multiple-documents")
public class SignatureMultipleDocumentsController {

	private static final Logger LOG = LoggerFactory.getLogger(SignatureMultipleDocumentsController.class);

	private static final String SIGNATURE_PARAMETERS = "signature-multiple-documents";
	private static final String SIGNATURE_PROCESS = "nexu-signature-process";

	@Value("${nexuUrl}")
	private String nexuUrl;

	@Value("${nexuDownloadUrl}")
	private String downloadNexuUrl;

	@Autowired
	private SigningService signingService;

	@InitBinder
	public void initBinder(WebDataBinder binder) {
		binder.registerCustomEditor(SignatureForm.class, new EnumPropertyEditor(SignatureForm.class));
		binder.registerCustomEditor(ASiCContainerType.class, new EnumPropertyEditor(ASiCContainerType.class));
		binder.registerCustomEditor(SignatureLevel.class, new EnumPropertyEditor(SignatureLevel.class));
		binder.registerCustomEditor(DigestAlgorithm.class, new EnumPropertyEditor(DigestAlgorithm.class));
		binder.registerCustomEditor(EncryptionAlgorithm.class, new EnumPropertyEditor(EncryptionAlgorithm.class));
	}

	@RequestMapping(method = RequestMethod.GET)
	public String showSignatureParameters(Model model, HttpServletRequest request) {
		SignatureMultipleDocumentsForm signatureMultipleDocumentsForm = new SignatureMultipleDocumentsForm();
		model.addAttribute("signatureMultipleDocumentsForm", signatureMultipleDocumentsForm);
		model.addAttribute("downloadNexuUrl", downloadNexuUrl);
		return SIGNATURE_PARAMETERS;
	}

	@RequestMapping(method = RequestMethod.POST)
	public String sendSignatureParameters(Model model, HttpServletRequest response,
			@ModelAttribute("signatureMultipleDocumentsForm") @Valid SignatureMultipleDocumentsForm signatureMultipleDocumentsForm, BindingResult result) {
		if (result.hasErrors()) {
			if (LOG.isDebugEnabled()) {
				List<ObjectError> allErrors = result.getAllErrors();
				for (ObjectError error : allErrors) {
					LOG.debug(error.getDefaultMessage());
				}
			}
			return SIGNATURE_PARAMETERS;
		}
		model.addAttribute("signatureMultipleDocumentsForm", signatureMultipleDocumentsForm);
		model.addAttribute("digestAlgorithm", signatureMultipleDocumentsForm.getDigestAlgorithm());
		model.addAttribute("rootUrl", "sign-multiple-documents");
		model.addAttribute("nexuUrl", nexuUrl);
		return SIGNATURE_PROCESS;
	}

	@RequestMapping(value = "/get-data-to-sign", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public GetDataToSignResponse getDataToSign(Model model, @RequestBody @Valid DataToSignParams params,
			@ModelAttribute("signatureMultipleDocumentsForm") @Valid SignatureMultipleDocumentsForm signatureMultipleDocumentsForm, BindingResult result) {
		signatureMultipleDocumentsForm.setBase64Certificate(params.getSigningCertificate());
		signatureMultipleDocumentsForm.setBase64CertificateChain(params.getCertificateChain());
		signatureMultipleDocumentsForm.setEncryptionAlgorithm(params.getEncryptionAlgorithm());
		signatureMultipleDocumentsForm.setSigningDate(new Date());

		if (signatureMultipleDocumentsForm.isAddContentTimestamp()) {
			signatureMultipleDocumentsForm
					.setContentTimestamp(WebAppUtils.fromTimestampToken(signingService.getContentTimestamp(signatureMultipleDocumentsForm)));
		}

		model.addAttribute("signatureMultipleDocumentsForm", signatureMultipleDocumentsForm);

		ToBeSigned dataToSign = signingService.getDataToSign(signatureMultipleDocumentsForm);
		if (dataToSign == null) {
			return null;
		}

		GetDataToSignResponse responseJson = new GetDataToSignResponse();
		responseJson.setDataToSign(DatatypeConverter.printBase64Binary(dataToSign.getBytes()));
		return responseJson;
	}

	@RequestMapping(value = "/sign-document", method = RequestMethod.POST)
	@ResponseBody
	public SignDocumentResponse signDocument(Model model, @RequestBody @Valid SignatureValueAsString signatureValue,
			@ModelAttribute("signatureMultipleDocumentsForm") @Valid SignatureMultipleDocumentsForm signatureMultipleDocumentsForm, BindingResult result) {

		signatureMultipleDocumentsForm.setBase64SignatureValue(signatureValue.getSignatureValue());

		DSSDocument document = signingService.signDocument(signatureMultipleDocumentsForm);
		InMemoryDocument signedDocument = new InMemoryDocument(DSSUtils.toByteArray(document), document.getName(), document.getMimeType());
		model.addAttribute("signedDocument", signedDocument);

		SignDocumentResponse signedDocumentResponse = new SignDocumentResponse();
		signedDocumentResponse.setUrlToDownload("download");
		return signedDocumentResponse;
	}

	@RequestMapping(value = "/download", method = RequestMethod.GET)
	public String downloadSignedFile(@ModelAttribute("signedDocument") InMemoryDocument signedDocument, HttpServletResponse response) {
		try {
			MimeType mimeType = signedDocument.getMimeType();
			if (mimeType != null) {
				response.setContentType(mimeType.getMimeTypeString());
			}
			response.setHeader("Content-Transfer-Encoding", "binary");
			response.setHeader("Content-Disposition", "attachment; filename=\"" + signedDocument.getName() + "\"");
			Utils.copy(new ByteArrayInputStream(signedDocument.getBytes()), response.getOutputStream());

		} catch (Exception e) {
			LOG.error("An error occurred while pushing file in response : " + e.getMessage(), e);
		}
		return null;
	}

	@ModelAttribute("asicContainerTypes")
	public ASiCContainerType[] getASiCContainerTypes() {
		return ASiCContainerType.values();
	}

	@ModelAttribute("signatureForms")
	public SignatureForm[] getSignatureForms() {
		return new SignatureForm[] { SignatureForm.CAdES, SignatureForm.XAdES };
	}

	@ModelAttribute("digestAlgos")
	public DigestAlgorithm[] getDigestAlgorithms() {
		DigestAlgorithm[] algos = new DigestAlgorithm[] { DigestAlgorithm.SHA1, DigestAlgorithm.SHA224, DigestAlgorithm.SHA256, DigestAlgorithm.SHA384,
				DigestAlgorithm.SHA512 };
		return algos;
	}

	@ModelAttribute("isMockUsed")
	public boolean isMockUsed() {
		return signingService.isMockTSPSourceUsed();
	}

}
