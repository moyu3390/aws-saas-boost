package com.amazon.aws.partners.saasfactory.saasboost.dal.ddb;

import com.amazon.aws.partners.saasfactory.saasboost.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynamoTier {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamoTier.class);
    private static final TierAttribute PRIMARY_KEY = TierAttribute.id;
    public final Map<String, AttributeValue> attributes;

    private DynamoTier(Tier tier) {
        attributes = new HashMap<>();
        for (TierAttribute tierAttribute : TierAttribute.values()) {
            attributes.put(tierAttribute.name(), tierAttribute.fromTier(tier));
        }
        LOGGER.debug("Created DynamoTier: {}", attributes);
    }

    private Map<String, AttributeValue> attributesWithoutPrimaryKey() {
        Map<String, AttributeValue> toReturn = new HashMap<>(attributes);
        toReturn.remove(PRIMARY_KEY.name());
        return toReturn;
    }

    public String updateExpression() {
        // https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.UpdateExpressions.html
        List<String> setUpdates = new ArrayList<>();
        for (String attributeName : attributesWithoutPrimaryKey().keySet()) {
            setUpdates.add(String.format("#%s=:%s", attributeName, attributeName));
        }
        // return updateExpression.toString();
        return "SET " + String.join(",", setUpdates);
    }

    // because name is a reserved keyword in DynamoDB update expressions, we need to change the update expression from
    // something like
    //    SET name=:name,description=:description
    // to
    //    SET #name=:name,#description=:description
    public Map<String, String> updateAttributeNames() {
        Map<String, String> updateAttributeNames = new HashMap<>();
        for (String attributeName : attributesWithoutPrimaryKey().keySet()) {
            updateAttributeNames.put("#" + attributeName, attributeName);
        }
        return updateAttributeNames;
    }

    public Map<String, AttributeValue> updateAttributes() {
        Map<String, AttributeValue> updateAttributes = new HashMap<>();
        for (Map.Entry<String, AttributeValue> attribute : attributesWithoutPrimaryKey().entrySet()) {
            updateAttributes.put(":" + attribute.getKey(), attribute.getValue());
        }
        return updateAttributes;
    }

    public Map<String, AttributeValue> primaryKey() {
        return Map.of(PRIMARY_KEY.name(), attributes.get(PRIMARY_KEY.name()));
    }

    public static Map<String, AttributeValue> primaryKey(String id) {
        return Map.of(PRIMARY_KEY.name(), AttributeValue.builder().s(id).build());
    }

    public static Tier fromAttributes(Map<String, AttributeValue> attributes) {
        Tier.Builder tierBuilderInProgress = Tier.builder();
        for (TierAttribute tierAttribute : TierAttribute.values()) {
            tierAttribute.toTier(tierBuilderInProgress, attributes.get(tierAttribute.name()));
        }
        return tierBuilderInProgress.build();
    }

    public static DynamoTier fromTier(Tier tier) {
        return new DynamoTier(tier);
    }
}
