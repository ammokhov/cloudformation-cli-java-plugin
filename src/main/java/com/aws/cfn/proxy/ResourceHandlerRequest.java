package com.aws.cfn.proxy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.json.JSONObject;

/**
 * This interface describes the request object for the provisioning request passed to the implementor.
 * It is transformed from an instance of HandlerRequest by the LambdaWrapper to only items of concern
 * @param <T> Type of resource model being provisioned
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResourceHandlerRequest<T> {
    private String awsAccountId;
    private String nextToken;
    private String region;
    private String resourceType;
    private String resourceTypeVersion;
    private Credentials credentials;
    private T desiredResourceState;
    private T previousResourceState;

    /**
     * Custom context object to enable handlers to process re-invocation
     */
    private JSONObject callbackContext;
}
