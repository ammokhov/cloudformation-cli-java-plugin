/*
* Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/
package software.amazon.cloudformation;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.cloudformation.exceptions.BaseHandlerException;
import software.amazon.cloudformation.exceptions.FileScrubberException;
import software.amazon.cloudformation.exceptions.TerminalException;
import software.amazon.cloudformation.injection.CloudFormationProvider;
import software.amazon.cloudformation.injection.CloudWatchEventsProvider;
import software.amazon.cloudformation.injection.CloudWatchLogsProvider;
import software.amazon.cloudformation.injection.CloudWatchProvider;
import software.amazon.cloudformation.injection.CredentialsProvider;
import software.amazon.cloudformation.injection.SessionCredentialsProvider;
import software.amazon.cloudformation.loggers.CloudWatchLogHelper;
import software.amazon.cloudformation.loggers.CloudWatchLogPublisher;
import software.amazon.cloudformation.loggers.LambdaLogPublisher;
import software.amazon.cloudformation.loggers.LogPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisher;
import software.amazon.cloudformation.metrics.MetricsPublisherImpl;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.CallbackAdapter;
import software.amazon.cloudformation.proxy.CloudFormationCallbackAdapter;
import software.amazon.cloudformation.proxy.Credentials;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.HandlerRequest;
import software.amazon.cloudformation.proxy.LoggerProxy;
import software.amazon.cloudformation.proxy.MetricsPublisherProxy;
import software.amazon.cloudformation.proxy.OperationStatus;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.RequestContext;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.resource.ResourceTypeSchema;
import software.amazon.cloudformation.resource.SchemaValidator;
import software.amazon.cloudformation.resource.Serializer;
import software.amazon.cloudformation.resource.Validator;
import software.amazon.cloudformation.resource.exceptions.ValidationException;
import software.amazon.cloudformation.scheduler.CloudWatchScheduler;

public abstract class LambdaWrapper<ResourceT, CallbackT> implements RequestStreamHandler {

    public static final SdkHttpClient HTTP_CLIENT = ApacheHttpClient.builder().build();

    private static final List<Action> MUTATING_ACTIONS = Arrays.asList(Action.CREATE, Action.DELETE, Action.UPDATE);
    private static final int INVOCATION_TIMEOUT_MS = 60000;

    protected final Serializer serializer;
    protected LoggerProxy loggerProxy;
    protected MetricsPublisherProxy metricsPublisherProxy;

    // Keep lambda logger as the last fallback log delivery approach
    protected LambdaLogger lambdaLogger;

    // provider... prefix indicates credential provided by resource owner

    final CredentialsProvider platformCredentialsProvider;
    final CredentialsProvider providerCredentialsProvider;

    final CloudFormationProvider cloudFormationProvider;
    final CloudWatchProvider platformCloudWatchProvider;
    final CloudWatchProvider providerCloudWatchProvider;
    final CloudWatchEventsProvider platformCloudWatchEventsProvider;
    final CloudWatchLogsProvider cloudWatchLogsProvider;
    final SchemaValidator validator;
    final TypeReference<HandlerRequest<ResourceT, CallbackT>> typeReference;

    private CallbackAdapter<ResourceT> callbackAdapter;
    private MetricsPublisher platformMetricsPublisher;
    private MetricsPublisher providerMetricsPublisher;
    private CloudWatchScheduler scheduler;

    private LogPublisher platformLambdaLogger;
    private CloudWatchLogHelper cloudWatchLogHelper;
    private CloudWatchLogPublisher providerEventsLogger;

    protected LambdaWrapper() {
        this.platformCredentialsProvider = new SessionCredentialsProvider();
        this.providerCredentialsProvider = new SessionCredentialsProvider();
        this.cloudFormationProvider = new CloudFormationProvider(this.platformCredentialsProvider, HTTP_CLIENT);
        this.platformCloudWatchProvider = new CloudWatchProvider(this.platformCredentialsProvider, HTTP_CLIENT);
        this.providerCloudWatchProvider = new CloudWatchProvider(this.providerCredentialsProvider, HTTP_CLIENT);
        this.platformCloudWatchEventsProvider = new CloudWatchEventsProvider(this.platformCredentialsProvider, HTTP_CLIENT);
        this.cloudWatchLogsProvider = new CloudWatchLogsProvider(this.providerCredentialsProvider, HTTP_CLIENT);
        this.serializer = new Serializer();
        this.validator = new Validator();
        this.typeReference = getTypeReference();
    }

    /**
     * This .ctor provided for testing
     */
    public LambdaWrapper(final CallbackAdapter<ResourceT> callbackAdapter,
                         final CredentialsProvider platformCredentialsProvider,
                         final CredentialsProvider providerCredentialsProvider,
                         final CloudWatchLogPublisher providerEventsLogger,
                         final LogPublisher platformEventsLogger,
                         final MetricsPublisher platformMetricsPublisher,
                         final MetricsPublisher providerMetricsPublisher,
                         final CloudWatchScheduler scheduler,
                         final SchemaValidator validator,
                         final Serializer serializer,
                         final SdkHttpClient httpClient) {

        this.callbackAdapter = callbackAdapter;
        this.platformCredentialsProvider = platformCredentialsProvider;
        this.providerCredentialsProvider = providerCredentialsProvider;
        this.cloudFormationProvider = new CloudFormationProvider(this.platformCredentialsProvider, httpClient);
        this.platformCloudWatchProvider = new CloudWatchProvider(this.platformCredentialsProvider, httpClient);
        this.providerCloudWatchProvider = new CloudWatchProvider(this.providerCredentialsProvider, httpClient);
        this.platformCloudWatchEventsProvider = new CloudWatchEventsProvider(this.platformCredentialsProvider, httpClient);
        this.cloudWatchLogsProvider = new CloudWatchLogsProvider(this.providerCredentialsProvider, httpClient);
        this.providerEventsLogger = providerEventsLogger;
        this.platformLambdaLogger = platformEventsLogger;
        this.platformMetricsPublisher = platformMetricsPublisher;
        this.providerMetricsPublisher = providerMetricsPublisher;
        this.scheduler = scheduler;
        this.serializer = serializer;
        this.validator = validator;
        this.typeReference = getTypeReference();
    }

    /**
     * This function initialises dependencies which are depending on credentials
     * passed at function invoke and not available during construction
     */
    private void initialiseRuntime(final String resourceType,
                                   final Credentials platformCredentials,
                                   final Credentials providerCredentials,
                                   final String providerLogGroupName,
                                   final Context context,
                                   final String awsAccountId,
                                   final URI callbackEndpoint) {

        this.loggerProxy = new LoggerProxy();
        this.metricsPublisherProxy = new MetricsPublisherProxy();

        this.platformLambdaLogger = new LambdaLogPublisher(context.getLogger());
        this.loggerProxy.addLogPublisher(this.platformLambdaLogger);

        this.cloudFormationProvider.setCallbackEndpoint(callbackEndpoint);
        this.platformCredentialsProvider.setCredentials(platformCredentials);

        // Initialisation skipped if dependencies were set during injection (in unit
        // tests).
        // e.g. "if (this.platformMetricsPublisher == null)"
        if (this.platformMetricsPublisher == null) {
            // platformMetricsPublisher needs aws account id to differentiate metrics
            // namespace
            this.platformMetricsPublisher = new MetricsPublisherImpl(this.platformCloudWatchProvider, this.loggerProxy,
                                                                     awsAccountId, resourceType);
        }
        this.metricsPublisherProxy.addMetricsPublisher(this.platformMetricsPublisher);
        this.platformMetricsPublisher.refreshClient();

        // NOTE: providerCredentials and providerLogGroupName are null/not null in
        // sync.
        // Both are required parameters when LoggingConfig (optional) is provided when
        // 'RegisterType'.
        if (providerCredentials != null) {
            if (this.providerCredentialsProvider != null) {
                this.providerCredentialsProvider.setCredentials(providerCredentials);
            }

            if (this.providerMetricsPublisher == null) {
                this.providerMetricsPublisher = new MetricsPublisherImpl(this.providerCloudWatchProvider, this.loggerProxy,
                                                                         awsAccountId, resourceType);
            }
            this.metricsPublisherProxy.addMetricsPublisher(this.providerMetricsPublisher);
            this.providerMetricsPublisher.refreshClient();

            if (this.providerEventsLogger == null) {
                this.cloudWatchLogHelper = new CloudWatchLogHelper(this.cloudWatchLogsProvider, providerLogGroupName,
                                                                   context.getLogger(), this.metricsPublisherProxy);
                this.cloudWatchLogHelper.refreshClient();

                this.providerEventsLogger = new CloudWatchLogPublisher(this.cloudWatchLogsProvider, providerLogGroupName,
                                                                       this.cloudWatchLogHelper.prepareLogStream(),
                                                                       context.getLogger(), this.metricsPublisherProxy);
            }
            this.loggerProxy.addLogPublisher(this.providerEventsLogger);
            this.providerEventsLogger.refreshClient();
        }

        if (this.callbackAdapter == null) {
            this.callbackAdapter = new CloudFormationCallbackAdapter<>(this.cloudFormationProvider, this.loggerProxy,
                                                                       this.serializer, ResourceTypeSchema
                                                                           .load(provideResourceSchemaJSONObject()));
        }
        this.callbackAdapter.refreshClient();

        if (this.scheduler == null) {
            this.scheduler = new CloudWatchScheduler(this.platformCloudWatchEventsProvider, this.loggerProxy, this.serializer);
        }
        this.scheduler.refreshClient();
    }

    @Override
    public void handleRequest(final InputStream inputStream, final OutputStream outputStream, final Context context)
        throws IOException,
        TerminalException {

        this.lambdaLogger = context.getLogger();
        ProgressEvent<ResourceT, CallbackT> handlerResponse = null;
        HandlerRequest<ResourceT, CallbackT> request = null;
        scrubFiles();
        try {
            if (inputStream == null) {
                throw new TerminalException("No request object received");
            }

            String input = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            JSONObject rawInput = new JSONObject(new JSONTokener(input));

            // deserialize incoming payload to modelled request
            request = this.serializer.deserialize(input, typeReference);
            handlerResponse = processInvocation(rawInput, request, context);
        } catch (final ValidationException e) {
            String message;
            String fullExceptionMessage = ValidationException.buildFullExceptionMessage(e);
            if (!StringUtils.isEmpty(fullExceptionMessage)) {
                message = String.format("Model validation failed (%s)", fullExceptionMessage);
            } else {
                message = "Model validation failed with unknown cause.";
            }

            publishExceptionMetric(request == null ? null : request.getAction(), e, HandlerErrorCode.InvalidRequest);
            handlerResponse = ProgressEvent.defaultFailureHandler(new TerminalException(message, e),
                HandlerErrorCode.InvalidRequest);
        } catch (final Throwable e) {
            // Exceptions are wrapped as a consistent error response to the caller (i.e;
            // CloudFormation)
            e.printStackTrace(); // for root causing - logs to LambdaLogger by default
            handlerResponse = ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InternalFailure);
            if (request != null && request.getRequestData() != null && MUTATING_ACTIONS.contains(request.getAction())) {
                handlerResponse.setResourceModel(request.getRequestData().getResourceProperties());
            }
            if (request != null) {
                publishExceptionMetric(request.getAction(), e, HandlerErrorCode.InternalFailure);
            }

        } finally {
            // A response will be output on all paths, though CloudFormation will
            // not block on invoking the handlers, but rather listen for callbacks
            writeResponse(outputStream,
                createProgressResponse(handlerResponse, request != null ? request.getBearerToken() : null));
        }
    }

    private ProgressEvent<ResourceT, CallbackT>
        processInvocation(final JSONObject rawRequest, final HandlerRequest<ResourceT, CallbackT> request, final Context context)
            throws IOException,
            TerminalException {

        assert request != null : "Invalid request object received";

        if (request.getRequestData() == null) {
            throw new TerminalException("Invalid request object received");
        }

        if (MUTATING_ACTIONS.contains(request.getAction())) {
            if (request.getRequestData().getResourceProperties() == null) {
                throw new TerminalException("Invalid resource properties object received");
            }
        }

        if (StringUtils.isEmpty(request.getResponseEndpoint())) {
            throw new TerminalException("No callback endpoint received");
        }

        // ensure required execution credentials have been passed and inject them
        if (request.getRequestData().getPlatformCredentials() == null) {
            throw new TerminalException("Missing required platform credentials");
        }

        // initialise dependencies with platform credentials
        initialiseRuntime(request.getResourceType(), request.getRequestData().getPlatformCredentials(),
            request.getRequestData().getProviderCredentials(), request.getRequestData().getProviderLogGroupName(), context,
            request.getAwsAccountId(), URI.create(request.getResponseEndpoint()));

        // transform the request object to pass to caller
        ResourceHandlerRequest<ResourceT> resourceHandlerRequest = transform(request);

        RequestContext<CallbackT> requestContext = request.getRequestContext();

        if (requestContext == null || requestContext.getInvocation() == 0) {
            // Acknowledge the task for first time invocation
            this.callbackAdapter.reportProgress(request.getBearerToken(), null, OperationStatus.IN_PROGRESS,
                OperationStatus.PENDING, null, null);
        }

        if (requestContext != null) {
            // If this invocation was triggered by a 're-invoke' CloudWatch Event, clean it
            // up
            String cloudWatchEventsRuleName = requestContext.getCloudWatchEventsRuleName();
            if (!StringUtils.isBlank(cloudWatchEventsRuleName)) {
                this.scheduler.cleanupCloudWatchEvents(cloudWatchEventsRuleName, requestContext.getCloudWatchEventsTargetId());
                log(String.format("Cleaned up previous Request Context of Rule %s and Target %s",
                    requestContext.getCloudWatchEventsRuleName(), requestContext.getCloudWatchEventsTargetId()));
            }
        }

        this.metricsPublisherProxy.publishInvocationMetric(Instant.now(), request.getAction());

        // for CUD actions, validate incoming model - any error is a terminal failure on
        // the invocation
        // NOTE: we validate the raw pre-deserialized payload to account for lenient
        // serialization.
        // Here, we want to surface ALL input validation errors to the caller.
        boolean isMutatingAction = MUTATING_ACTIONS.contains(request.getAction());
        if (isMutatingAction) {
            // validate entire incoming payload, including extraneous fields which
            // are stripped by the Serializer (due to FAIL_ON_UNKNOWN_PROPERTIES setting)
            JSONObject rawModelObject = rawRequest.getJSONObject("requestData").getJSONObject("resourceProperties");
            try {
                validateModel(rawModelObject);
            } catch (final ValidationException e) {
                // TODO: we'll need a better way to expose the stack of causing exceptions for
                // user feedback
                StringBuilder validationMessageBuilder = new StringBuilder();
                if (!StringUtils.isEmpty(e.getMessage())) {
                    validationMessageBuilder.append(String.format("Model validation failed (%s)", e.getMessage()));
                } else {
                    validationMessageBuilder.append("Model validation failed with unknown cause.");
                }
                List<ValidationException> es = e.getCausingExceptions();
                if (CollectionUtils.isNotEmpty(es)) {
                    for (RuntimeException cause : es) {
                        if (cause instanceof ValidationException) {
                            validationMessageBuilder.append(
                                String.format("%n%s (%s)", cause.getMessage(), ((ValidationException) cause).getSchemaPointer()));
                        }
                    }
                }
                publishExceptionMetric(request.getAction(), e, HandlerErrorCode.InvalidRequest);
                this.callbackAdapter.reportProgress(request.getBearerToken(), HandlerErrorCode.InvalidRequest,
                    OperationStatus.FAILED, OperationStatus.IN_PROGRESS, null, validationMessageBuilder.toString());
                return ProgressEvent.defaultFailureHandler(new TerminalException(validationMessageBuilder.toString(), e),
                    HandlerErrorCode.InvalidRequest);
            }
        }

        // last mile proxy creation with passed-in credentials (unless we are operating
        // in a non-AWS model)
        AmazonWebServicesClientProxy awsClientProxy = null;
        if (request.getRequestData().getCallerCredentials() != null) {
            awsClientProxy = new AmazonWebServicesClientProxy(requestContext == null, this.loggerProxy,
                                                              request.getRequestData().getCallerCredentials(),
                                                              () -> (long) context.getRemainingTimeInMillis());
        }

        boolean computeLocally = true;
        ProgressEvent<ResourceT, CallbackT> handlerResponse = null;

        while (computeLocally) {
            // rebuild callback context on each invocation cycle
            requestContext = request.getRequestContext();
            CallbackT callbackContext = (requestContext != null) ? requestContext.getCallbackContext() : null;

            handlerResponse = wrapInvocationAndHandleErrors(awsClientProxy, resourceHandlerRequest, request, callbackContext);

            // report the progress status back to configured endpoint on
            // mutating/potentially asynchronous actions

            if (isMutatingAction) {
                this.callbackAdapter.reportProgress(request.getBearerToken(), handlerResponse.getErrorCode(),
                    handlerResponse.getStatus(), OperationStatus.IN_PROGRESS, handlerResponse.getResourceModel(),
                    handlerResponse.getMessage());
            } else if (handlerResponse.getStatus() == OperationStatus.IN_PROGRESS) {
                throw new TerminalException("READ and LIST handlers must return synchronously.");
            }
            // When the handler responses IN_PROGRESS with a callback delay, we trigger a
            // callback to re-invoke
            // the handler for the Resource type to implement stabilization checks and
            // long-poll creation checks
            computeLocally = scheduleReinvocation(request, handlerResponse, context);
        }

        return handlerResponse;
    }

    private void
        logUnhandledError(final String errorDescription, final HandlerRequest<ResourceT, CallbackT> request, final Throwable e) {
        log(String.format("%s in a %s action on a %s: %s%n%s", errorDescription, request.getAction(), request.getResourceType(),
            e.toString(), ExceptionUtils.getStackTrace(e)));
    }

    /**
     * Invokes the handler implementation for the request, and wraps with try-catch
     * to consistently handle certain classes of errors and correctly map those to
     * the appropriate HandlerErrorCode Also wraps the invocation in last-mile
     * timing metrics
     */
    private ProgressEvent<ResourceT, CallbackT>
        wrapInvocationAndHandleErrors(final AmazonWebServicesClientProxy awsClientProxy,
                                      final ResourceHandlerRequest<ResourceT> resourceHandlerRequest,
                                      final HandlerRequest<ResourceT, CallbackT> request,
                                      final CallbackT callbackContext) {

        Date startTime = Date.from(Instant.now());
        try {
            ProgressEvent<ResourceT, CallbackT> handlerResponse = invokeHandler(awsClientProxy, resourceHandlerRequest,
                request.getAction(), callbackContext);
            if (handlerResponse != null) {
                this.log(String.format("Handler returned %s", handlerResponse.getStatus()));
            } else {
                this.log("Handler returned null");
                throw new TerminalException("Handler failed to provide a response.");
            }

            return handlerResponse;
        } catch (final BaseHandlerException e) {
            publishExceptionMetric(request.getAction(), e, e.getErrorCode());
            logUnhandledError(e.getMessage(), request, e);
            return ProgressEvent.defaultFailureHandler(e, e.getErrorCode());
        } catch (final AmazonServiceException e) {
            publishExceptionMetric(request.getAction(), e, HandlerErrorCode.GeneralServiceException);
            logUnhandledError("A downstream service error occurred", request, e);
            return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.GeneralServiceException);
        } catch (final Throwable e) {
            publishExceptionMetric(request.getAction(), e, HandlerErrorCode.InternalFailure);
            logUnhandledError("An unknown error occurred ", request, e);
            return ProgressEvent.defaultFailureHandler(e, HandlerErrorCode.InternalFailure);
        } finally {
            Date endTime = Date.from(Instant.now());
            metricsPublisherProxy.publishDurationMetric(Instant.now(), request.getAction(),
                (endTime.getTime() - startTime.getTime()));
        }

    }

    private Response<ResourceT> createProgressResponse(final ProgressEvent<ResourceT, CallbackT> progressEvent,
                                                       final String bearerToken) {

        Response<ResourceT> response = new Response<>();
        response.setMessage(progressEvent.getMessage());
        response.setOperationStatus(progressEvent.getStatus());
        response.setResourceModel(progressEvent.getResourceModel());
        response.setErrorCode(progressEvent.getErrorCode());
        response.setBearerToken(bearerToken);
        response.setResourceModels(progressEvent.getResourceModels());
        response.setNextToken(progressEvent.getNextToken());

        return response;
    }

    private void writeResponse(final OutputStream outputStream, final Response<ResourceT> response) throws IOException {

        String output = this.serializer.serialize(response);
        outputStream.write(output.getBytes(StandardCharsets.UTF_8));
        outputStream.close();
    }

    private void validateModel(final JSONObject modelObject) throws ValidationException, IOException {
        JSONObject resourceSchemaJSONObject = provideResourceSchemaJSONObject();
        if (resourceSchemaJSONObject == null) {
            throw new TerminalException("Unable to validate incoming model as no schema was provided.");
        }

        TypeReference<ResourceT> modelTypeReference = getModelTypeReference();

        // deserialize incoming payload to modelled request
        ResourceT deserializedModel;
        try {
            deserializedModel = this.serializer.deserializeStrict(modelObject.toString(), modelTypeReference);
        } catch (UnrecognizedPropertyException e) {
            throw new ValidationException(String.format("#: extraneous key [%s] is not permitted", e.getPropertyName()),
                                          "additionalProperties", "#");
        }

        JSONObject serializedModel = new JSONObject(this.serializer.serialize(deserializedModel));
        this.validator.validateObject(serializedModel, resourceSchemaJSONObject);
    }

    /**
     * Managed scheduling of handler re-invocations.
     *
     * @param request the original request to the function
     * @param handlerResponse the previous response from handler
     * @param context LambdaContext granting runtime metadata
     * @return boolean indicating whether to continue invoking locally, or exit for
     *         async reinvoke
     */
    private boolean scheduleReinvocation(final HandlerRequest<ResourceT, CallbackT> request,
                                         final ProgressEvent<ResourceT, CallbackT> handlerResponse,
                                         final Context context) {

        if (handlerResponse.getStatus() != OperationStatus.IN_PROGRESS) {
            // no reinvoke required
            return false;
        }

        RequestContext<CallbackT> reinvocationContext = new RequestContext<>();
        RequestContext<CallbackT> requestContext = request.getRequestContext();

        int counter = 1;
        if (requestContext != null) {
            counter += requestContext.getInvocation();
        }
        reinvocationContext.setInvocation(counter);

        reinvocationContext.setCallbackContext(handlerResponse.getCallbackContext());
        request.setRequestContext(reinvocationContext);

        // when a handler requests a sub-minute callback delay, and if the lambda
        // invocation
        // has enough runtime (with 20% buffer), we can reschedule from a thread wait
        // otherwise we re-invoke through CloudWatchEvents which have a granularity of
        // minutes
        // This also guarantees a maximum of a minute of execution time per local
        // reinvocation
        if ((handlerResponse.getCallbackDelaySeconds() < 60) && context
            .getRemainingTimeInMillis() > Math.abs(handlerResponse.getCallbackDelaySeconds()) * 1200 + INVOCATION_TIMEOUT_MS) {
            log(String.format("Scheduling re-invoke locally after %s seconds, with Context {%s}",
                handlerResponse.getCallbackDelaySeconds(), reinvocationContext.toString()));
            sleepUninterruptibly(handlerResponse.getCallbackDelaySeconds(), TimeUnit.SECONDS);
            return true;
        }

        log(String.format("Scheduling re-invoke with Context {%s}", reinvocationContext.toString()));
        try {
            int callbackDelayMinutes = Math.abs(handlerResponse.getCallbackDelaySeconds() / 60);
            this.scheduler.rescheduleAfterMinutes(context.getInvokedFunctionArn(), callbackDelayMinutes, request);
        } catch (final Throwable e) {
            this.log(String.format("Failed to schedule re-invoke, caused by %s", e.toString()));
            handlerResponse.setMessage(e.getMessage());
            handlerResponse.setStatus(OperationStatus.FAILED);
            handlerResponse.setErrorCode(HandlerErrorCode.InternalFailure);
        }

        return false;
    }

    /**
     * Transforms the incoming request to the subset of typed models which the
     * handler implementor needs
     *
     * @param request The request as passed from the caller (e.g; CloudFormation)
     *            which contains additional context to inform the LambdaWrapper
     *            itself, and is not needed by the handler implementations
     * @return A converted ResourceHandlerRequest model
     */
    protected abstract ResourceHandlerRequest<ResourceT> transform(HandlerRequest<ResourceT, CallbackT> request)
        throws IOException;

    /**
     * Handler implementation should implement this method to provide the schema for
     * validation
     *
     * @return An JSONObject of the resource schema for the provider
     */
    protected abstract JSONObject provideResourceSchemaJSONObject();

    /**
     * Handler implementation should implement this method to provide any
     * resource-level tags defined for their resource type
     *
     * @return An JSONObject of the resource schema for the provider
     */
    protected abstract Map<String, String> provideResourceDefinedTags(ResourceT resourceModel);

    /**
     * Implemented by the handler package as the key entry point.
     */
    public abstract ProgressEvent<ResourceT, CallbackT> invokeHandler(AmazonWebServicesClientProxy proxy,
                                                                      ResourceHandlerRequest<ResourceT> request,
                                                                      Action action,
                                                                      CallbackT callbackContext)
        throws Exception;

    /**
     * null-safe exception metrics delivery
     */
    private void publishExceptionMetric(final Action action, final Throwable ex, final HandlerErrorCode handlerErrorCode) {
        if (this.metricsPublisherProxy != null) {
            this.metricsPublisherProxy.publishExceptionMetric(Instant.now(), action, ex, handlerErrorCode);
        } else {
            // Lambda logger is the only fallback if metrics publisher proxy is not
            // initialized.
            lambdaLogger.log(ex.toString());
        }
    }

    /**
     * null-safe logger redirect
     *
     * @param message A string containing the event to log.
     */
    private void log(final String message) {
        if (this.loggerProxy != null) {
            this.loggerProxy.log(String.format("%s%n", message));
        } else {
            // Lambda logger is the only fallback if metrics publisher proxy is not
            // initialized.
            lambdaLogger.log(message);
        }
    }

    protected abstract TypeReference<HandlerRequest<ResourceT, CallbackT>> getTypeReference();

    protected abstract TypeReference<ResourceT> getModelTypeReference();

    protected void scrubFiles() {
        try {
            FileUtils.cleanDirectory(FileUtils.getTempDirectory());
        } catch (IOException e) {
            log(e.getMessage());
            publishExceptionMetric(null, new FileScrubberException(e), HandlerErrorCode.InternalFailure);
        }
    }

    /**
     * Combines the tags supplied by the caller (e.g; CloudFormation) into a single
     * Map which represents the desired final set of tags to be applied to this
     * resource. User-defined tags
     *
     * @param request The request object contains the new set of tags to be applied
     *            at a Stack level. These will be overridden with any resource-level
     *            tags which are specified as a direct resource property.
     * @return a Map of Tag names to Tag values
     */
    @VisibleForTesting
    protected Map<String, String> getDesiredResourceTags(final HandlerRequest<ResourceT, CallbackT> request) {
        Map<String, String> desiredResourceTags = new HashMap<>();

        if (request != null && request.getRequestData() != null) {
            replaceInMap(desiredResourceTags, request.getRequestData().getStackTags());
            replaceInMap(desiredResourceTags, provideResourceDefinedTags(request.getRequestData().getResourceProperties()));
        }

        return desiredResourceTags;
    }

    private void replaceInMap(final Map<String, String> targetMap, final Map<String, String> sourceMap) {
        if (targetMap == null) {
            return;
        }
        if (sourceMap == null || sourceMap.isEmpty()) {
            return;
        }

        targetMap.putAll(sourceMap);
    }

}
