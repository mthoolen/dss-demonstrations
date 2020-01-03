package eu.europa.esig.dss.web.controller;

import java.io.ByteArrayInputStream;
import java.util.Date;

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
import eu.europa.esig.dss.web.model.SignatureDigestForm;
import eu.europa.esig.dss.web.model.SignatureValueAsString;
import eu.europa.esig.dss.web.service.SigningService;

@Controller
@SessionAttributes(value = { "signatureDigestForm", "signedDocument" })
@RequestMapping(value = "/sign-a-digest")
public class DigestController {

	private static final Logger logger = LoggerFactory.getLogger(DigestController.class);

	private static final String DIGEST_PARAMETERS = "digest-parameters";
	private static final String SIGNATURE_PROCESS = "nexu-signature-process";

	@Value("${nexuUrl}")
	private String nexuUrl;

	@Value("${baseUrl}")
	private String downloadNexuUrl;

	@Autowired
	private SigningService signingService;

	@InitBinder
	public void initBinder(WebDataBinder binder) {
		binder.registerCustomEditor(SignatureForm.class, new EnumPropertyEditor(SignatureForm.class));
		binder.registerCustomEditor(SignatureLevel.class, new EnumPropertyEditor(SignatureLevel.class));
		binder.registerCustomEditor(DigestAlgorithm.class, new EnumPropertyEditor(DigestAlgorithm.class));
		binder.registerCustomEditor(EncryptionAlgorithm.class, new EnumPropertyEditor(EncryptionAlgorithm.class));
	}

	@RequestMapping(method = RequestMethod.GET)
	public String showSignatureParameters(Model model, HttpServletRequest request) {
		SignatureDigestForm signatureDigestForm = new SignatureDigestForm();
		signatureDigestForm.setDigestAlgorithm(DigestAlgorithm.SHA256);
		model.addAttribute("signatureDigestForm", signatureDigestForm);
		model.addAttribute("downloadNexuUrl", downloadNexuUrl);
		return DIGEST_PARAMETERS;
	}

	@RequestMapping(method = RequestMethod.POST)
	public String sendSignatureParameters(Model model, HttpServletRequest response,
			@ModelAttribute("signatureDigestForm") @Valid SignatureDigestForm signatureDigestForm, BindingResult result) {
		if (result.hasErrors()) {
			for (ObjectError error : result.getAllErrors()) {
				logger.error(error.getDefaultMessage());
			}
			return DIGEST_PARAMETERS;
		}
		model.addAttribute("signatureDigestForm", signatureDigestForm);
		model.addAttribute("digestAlgorithm", signatureDigestForm.getDigestAlgorithm());
		model.addAttribute("rootUrl", "sign-a-digest");
		model.addAttribute("nexuUrl", nexuUrl);
		return SIGNATURE_PROCESS;
	}

	@RequestMapping(value = "/get-data-to-sign", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public GetDataToSignResponse getDataToSign(Model model, @RequestBody @Valid DataToSignParams params,
			@ModelAttribute("signatureDigestForm") @Valid SignatureDigestForm signatureDigestForm, BindingResult result) {
		signatureDigestForm.setBase64Certificate(params.getSigningCertificate());
		signatureDigestForm.setBase64CertificateChain(params.getCertificateChain());
		signatureDigestForm.setEncryptionAlgorithm(params.getEncryptionAlgorithm());
		signatureDigestForm.setSigningDate(new Date());

		if (signatureDigestForm.isAddContentTimestamp()) {
			signatureDigestForm.setContentTimestamp(WebAppUtils.fromTimestampToken(signingService.getContentTimestamp(signatureDigestForm)));
		}

		model.addAttribute("signatureDigestForm", signatureDigestForm);

		ToBeSigned dataToSign = signingService.getDataToSign(signatureDigestForm);
		if (dataToSign == null) {
			return null;
		}

		GetDataToSignResponse responseJson = new GetDataToSignResponse();
		responseJson.setDataToSign(DatatypeConverter.printBase64Binary(dataToSign.getBytes()));
		return responseJson;
	}

	@RequestMapping(value = "/sign-document", method = RequestMethod.POST)
	@ResponseBody
	public SignDocumentResponse signDigest(Model model, @RequestBody @Valid SignatureValueAsString signatureValue,
			@ModelAttribute("signatureDigestForm") @Valid SignatureDigestForm signatureDigestForm, BindingResult result) {

		signatureDigestForm.setBase64SignatureValue(signatureValue.getSignatureValue());

		DSSDocument document = signingService.signDigest(signatureDigestForm);
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
			logger.error("An error occurred while pushing file in response : " + e.getMessage(), e);
		}
		return null;
	}

	@ModelAttribute("signatureForms")
	public SignatureForm[] getSignatureForms() {
		return new SignatureForm[] { SignatureForm.CAdES, SignatureForm.XAdES };
	}

	@ModelAttribute("digestAlgos")
	public DigestAlgorithm[] getDigestAlgorithms() {
		DigestAlgorithm[] algos = new DigestAlgorithm[] { DigestAlgorithm.SHA1, DigestAlgorithm.SHA256, DigestAlgorithm.SHA384,
				DigestAlgorithm.SHA512 };
		return algos;
	}
}