package com.amazon.aws.partners.saasfactory.saasboost.dal.ddb;

import com.amazon.aws.partners.saasfactory.saasboost.dal.exception.TierNotFoundException;
import com.amazon.aws.partners.saasfactory.saasboost.model.Tier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DynamoTierDataStoreGetTierTest {
    private static final String TABLE_NAME = "test-table";
    private static final String VALID_ID = "abc-123-id-456";
    private static final String VALID_DESC = "gold tier description\n123";
    private static final String VALID_NAME = "gold-tier";
    private static final Tier VALID_TIER = Tier.builder()
            .name(VALID_NAME).id(VALID_ID).description(VALID_DESC).build();
    private static final ArgumentMatcher<GetItemRequest> VALID_REQUEST =
            getItemRequest -> getItemRequest != null && getItemRequest.hasKey()
                    && VALID_ID.equals(getItemRequest.key().get(TierAttribute.id.name()).s());
    private static final ArgumentMatcher<GetItemRequest> INVALID_REQUEST =
            getItemRequest -> !VALID_REQUEST.matches(getItemRequest);

    private DynamoDbClient mockDdb;
    private ArgumentCaptor<GetItemRequest> requestArgumentCaptor;
    private DynamoTierDataStore dynamoTierDataStore;

    @Before
    public void setup() {
        mockDdb = mock(DynamoDbClient.class);
        final GetItemResponse validResponse = GetItemResponse.builder()
                .item(Map.of(
                        TierAttribute.id.name(), AttributeValue.builder().s(VALID_ID).build(),
                        TierAttribute.description.name(), AttributeValue.builder().s(VALID_DESC).build(),
                        TierAttribute.name.name(), AttributeValue.builder().s(VALID_NAME).build()))
                .build();
        final GetItemResponse invalidResponse = GetItemResponse.builder().build();
        when(mockDdb.getItem(ArgumentMatchers.argThat(VALID_REQUEST))).thenReturn(validResponse);
        when(mockDdb.getItem(ArgumentMatchers.argThat(INVALID_REQUEST))).thenReturn(invalidResponse);

        requestArgumentCaptor = ArgumentCaptor.forClass(GetItemRequest.class);
        dynamoTierDataStore = new DynamoTierDataStore(mockDdb, TABLE_NAME);
    }

    public void verifyGetItemRequest(String expectedId) {
        // verify getItem was called at least once
        verify(mockDdb).getItem(requestArgumentCaptor.capture());
        // assert the passed getItem request matches expectations
        GetItemRequest capturedRequest = requestArgumentCaptor.getValue();
        assertEquals("Requested table name should match configuration.",
                TABLE_NAME, capturedRequest.tableName());
        assertTrue(capturedRequest.hasKey());
        assertEquals("Requested primary key should be id:configuredId.",
                Map.of(TierAttribute.id.name(), AttributeValue.builder().s(expectedId).build()),
                capturedRequest.key());
        assertTrue("GetItem should use Consistent Reads", capturedRequest.consistentRead());
    }

    @Test
    public void validId() throws TierNotFoundException {
        Tier retrievedTier = dynamoTierDataStore.getTier(VALID_ID);
        verifyGetItemRequest(VALID_ID);
        // assert the retrievedTier matches expectations
        assertEquals("Tiers should be equal.", VALID_TIER, retrievedTier);
    }

    @Test(expected = TierNotFoundException.class)
    public void getTierTest_nullId() throws TierNotFoundException {
        dynamoTierDataStore.getTier(null);
    }

    @Test
    public void getTierTest_invalidNonnullId() {
        // If there is no matching item, GetItem does not return any data and there will be no Item element in the response.
        final String invalidId = VALID_ID + "-different";
        try {
            dynamoTierDataStore.getTier(invalidId);
            fail("getTier with a non-existent id should throw TierNotFoundException");
        } catch (TierNotFoundException tnfe) {
            verifyGetItemRequest(invalidId);
        }
    }
}
