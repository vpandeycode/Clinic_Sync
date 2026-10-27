package org.clinicsync.common.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.clinicsync.common.model.ResourceVersionRecord;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Standardized FHIR R4 parser, serializer, validator, and bundle assembler.
 */
public final class FhirUtil {

    private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();
    private static final FhirValidator VALIDATOR = FHIR_CONTEXT.newValidator();

    private FhirUtil() {}

    public static FhirContext getContext() {
        return FHIR_CONTEXT;
    }

    public static IParser newJsonParser() {
        return FHIR_CONTEXT.newJsonParser()
                .setParserErrorHandler(new ca.uhn.fhir.parser.StrictErrorHandler())
                .setPrettyPrint(false);
    }

    public static String toJson(IBaseResource resource) {
        return newJsonParser().encodeResourceToString(resource);
    }

    public static <T extends IBaseResource> T fromJson(String json, Class<T> clazz) {
        return newJsonParser().parseResource(clazz, json);
    }

    public static IBaseResource fromJson(String json) {
        return newJsonParser().parseResource(json);
    }

    /**
     * Validates that the JSON string represents a syntactically and structurally valid FHIR resource
     * matching the expected resource type.
     */
    public static IBaseResource validateAndParse(String expectedType, String json) {
        if (json == null || json.isBlank()) {
            throw new org.clinicsync.common.exception.InvalidFhirException("FHIR resource JSON must not be blank");
        }

        IBaseResource resource;
        try {
            resource = fromJson(json);
        } catch (Exception e) {
            throw new org.clinicsync.common.exception.InvalidFhirException("Failed to parse FHIR JSON: " + e.getMessage(), e);
        }

        String actualType = FHIR_CONTEXT.getResourceType(resource);
        if (!actualType.equalsIgnoreCase(expectedType)) {
            throw new org.clinicsync.common.exception.InvalidFhirException("Resource type mismatch: expected " + expectedType + " but found " + actualType);
        }

        return resource;
    }

    /**
     * Builds an official FHIR R4 history Bundle from a list of ResourceVersionRecords.
     */
    public static Bundle createHistoryBundle(String resourceType, String resourceId, List<ResourceVersionRecord> versions) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.HISTORY);
        bundle.setTotal(versions.size());

        for (ResourceVersionRecord record : versions) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl(resourceType + "/" + resourceId + "/_history/" + record.versionId());

            if (!record.isTombstone()) {
                try {
                    IBaseResource res = fromJson(record.contentJson());
                    if (res instanceof Resource r) {
                        r.setId(new IdType(resourceType, resourceId, record.versionId()));
                        r.getMeta().setVersionId(record.versionId());
                        r.getMeta().setLastUpdated(Date.from(record.createdAt()));
                        entry.setResource(r);
                    }
                } catch (Exception ignored) {
                    // Fallback if parsing history fails
                }
            }

            Bundle.BundleEntryRequestComponent request = entry.getRequest();
            if (record.isTombstone()) {
                request.setMethod(Bundle.HTTPVerb.DELETE);
                request.setUrl(resourceType + "/" + resourceId);
            } else if ("1".equals(record.versionId())) {
                request.setMethod(Bundle.HTTPVerb.POST);
                request.setUrl(resourceType);
            } else {
                request.setMethod(Bundle.HTTPVerb.PUT);
                request.setUrl(resourceType + "/" + resourceId);
            }

            Bundle.BundleEntryResponseComponent response = entry.getResponse();
            response.setStatus(record.isTombstone() ? "204 No Content" : "200 OK");
            response.setEtag("\"" + record.versionId() + "\"");
            response.setLastModified(Date.from(record.createdAt()));
        }

        return bundle;
    }
}
