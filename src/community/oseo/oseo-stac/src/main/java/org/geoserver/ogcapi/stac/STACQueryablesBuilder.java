/* (c) 2021 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.ogcapi.stac;

import static org.geoserver.ogcapi.stac.TemplatePropertyVisitor.JSON_PROPERTY_TYPE;

import io.swagger.v3.oas.models.media.Schema;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.geoserver.featurestemplating.builders.AbstractTemplateBuilder;
import org.geoserver.featurestemplating.builders.TemplateBuilder;
import org.geoserver.featurestemplating.builders.impl.CompositeBuilder;
import org.geoserver.featurestemplating.builders.impl.DynamicValueBuilder;
import org.geoserver.ogcapi.AttributeType;
import org.geoserver.ogcapi.Queryables;
import org.geoserver.ogcapi.QueryablesBuilder;
import org.geoserver.opensearch.eo.OSEOInfo;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.filter.visitor.ExpressionTypeVisitor;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.expression.Expression;

public class STACQueryablesBuilder {

    public static final String GEOMETRY_SCHEMA_REF =
            "https://schemas.stacspec.org/v1.0.0/item-spec/json-schema/item.json#/geometry";
    public static final String ID_SCHEMA_REF =
            "https://schemas.stacspec.org/v1.0.0/item-spec/json-schema/item.json#/id";
    public static final String COLLECTION_SCHEMA_REF =
            "https://schemas.stacspec.org/v1.0.0/item-spec/json-schema/item.json#/collection";
    public static final String DATETIME_SCHEMA_REF =
            "https://schemas.stacspec.org/v1.0.0/item-spec/json-schema/datetime.json#/properties/datetime";

    static final String DEFINED_QUERYABLES_PROPERTY = "queryables";
    private static Set<String> SKIP_PROPERTIES = new HashSet<>();

    private static final FilterFactory2 FF = CommonFactoryFinder.getFilterFactory2();

    static final Map<String, String> WELL_KNOWN_PROPERTIES;

    static {
        WELL_KNOWN_PROPERTIES = new HashMap<>();
        WELL_KNOWN_PROPERTIES.put("geometry", "footprint");
        WELL_KNOWN_PROPERTIES.put("id", "identifier");
        WELL_KNOWN_PROPERTIES.put("collection", "parentIdentifier");
        WELL_KNOWN_PROPERTIES.put("datetime", "timeStart");
    }

    static {
        // replaced by "datetime"
        SKIP_PROPERTIES.add("start_datetime");
        SKIP_PROPERTIES.add("end_datetime");
        SKIP_PROPERTIES.add("datetime");
        // replaced by "geometry"
        SKIP_PROPERTIES.add("footprint");
        // replaced by "id"
        SKIP_PROPERTIES.add("eoIdentifier");
        // replaced by "collection"
        SKIP_PROPERTIES.add("parentIdentifier");
    }

    private final TemplateBuilder template;
    private final Queryables queryables;
    private final FeatureType itemsSchema;
    private final Feature sampleFeature;
    private final Feature collection;
    private final OSEOInfo oseoInfo;

    public STACQueryablesBuilder(
            String id,
            TemplateBuilder template,
            FeatureType itemsSchema,
            Feature sampleFeature,
            Feature collection,
            OSEOInfo oseoInfo) {
        this.queryables = new QueryablesBuilder(id).build();
        this.oseoInfo = oseoInfo;
        this.queryables.setProperties(new LinkedHashMap<>());
        this.template = template;
        this.itemsSchema = itemsSchema;
        this.sampleFeature = sampleFeature;
        this.collection = collection;
    }

    Queryables getQueryables() throws IOException {
        AbstractTemplateBuilder features = lookupBuilder(template, "features");
        Map<String, Schema> properties = this.queryables.getProperties();
        if (features != null) {
            AbstractTemplateBuilder builder = lookupBuilder(features, "properties");
            if (builder != null) {
                Set<String> preconfiguredQueryables = getPreconfiguredQueryables();
                TemplatePropertyVisitor visitor =
                        new TemplatePropertyVisitor(
                                builder,
                                sampleFeature,
                                (path, vb) -> {
                                    if (SKIP_PROPERTIES.contains(path)) return;
                                    // skip property if not among the preconfigured ones
                                    if (preconfiguredQueryables != null
                                            && !preconfiguredQueryables.contains(path)) return;
                                    properties.put(path, getSchema(vb));
                                });
                visitor.visit();
            }
        }
        // force in the extra properties not found under properties
        properties.put("id", getSchema("ID", ID_SCHEMA_REF));
        properties.put("collection", getSchema("Collection", COLLECTION_SCHEMA_REF));
        properties.put("geometry", getSchema("Geometry", GEOMETRY_SCHEMA_REF));
        properties.put("datetime", getSchema("Datetime", DATETIME_SCHEMA_REF));

        return this.queryables;
    }

    public Set<String> getPreconfiguredQueryables() {
        Set<String> result = null;

        // global queryables apply to every collection too
        if (oseoInfo != null && !oseoInfo.getGlobalQueryables().isEmpty()) {
            result = new LinkedHashSet<>(oseoInfo.getGlobalQueryables());
        }

        // if there is a collection, then we use the pre-configured ones only if
        // there is a setting for them, otherwise return all of them, skipping the global ones too
        if (collection != null) {
            Optional<String[]> collectionQueryables =
                    Optional.ofNullable(collection.getProperty(DEFINED_QUERYABLES_PROPERTY))
                            .map(p -> (String[]) p.getValue());
            if (collectionQueryables.isPresent()) {
                if (result == null) result = new LinkedHashSet<>();
                result.addAll(Arrays.asList(collectionQueryables.get()));
            } else {
                return null;
            }
        }

        return result;
    }

    /**
     * Returns a map between the path names used for queryables, and the expression backing them in
     * the database
     *
     * @return
     * @throws IOException
     */
    Map<String, Expression> getExpressionMap() {
        AbstractTemplateBuilder features = lookupBuilder(template, "features");
        Map<String, Expression> result = new HashMap<>();
        if (features != null) {
            AbstractTemplateBuilder properties = lookupBuilder(features, "properties");
            if (properties != null) {
                Set<String> preconfiguredQueryables = getPreconfiguredQueryables();
                TemplatePropertyVisitor visitor =
                        new TemplatePropertyVisitor(
                                properties,
                                sampleFeature,
                                (path, vb) -> {
                                    if (SKIP_PROPERTIES.contains(path)) return;
                                    if (preconfiguredQueryables != null
                                            && !preconfiguredQueryables.contains(path)) return;
                                    result.put(
                                            path,
                                            vb.getXpath() == null ? vb.getCql() : vb.getXpath());
                                });
                visitor.visit();
            }
        }
        // force in the extra properties not found under properties
        WELL_KNOWN_PROPERTIES.forEach((k, v) -> result.put(k, FF.property(v)));

        return result;
    }

    private Schema<?> getSchema(String description, String ref) {
        Schema schema = new Schema();
        schema.set$ref(ref);
        schema.setDescription(description);
        return schema;
    }

    private AbstractTemplateBuilder lookupBuilder(TemplateBuilder parent, String key) {
        for (TemplateBuilder child : parent.getChildren()) {
            if (child instanceof AbstractTemplateBuilder) {
                AbstractTemplateBuilder atb = (AbstractTemplateBuilder) child;
                if (key.equals(atb.getKey(null))) {
                    return atb;
                } else if (atb instanceof CompositeBuilder && atb.getKey(null) == null) {
                    return lookupBuilder(atb, key);
                }
            }
        }
        return null;
    }

    private Schema getSchema(DynamicValueBuilder db) {
        Class<?> binding = null;
        if (db.getEncodingHints().hasHint(JSON_PROPERTY_TYPE)) {
            binding = (Class<?>) db.getEncodingHints().get(JSON_PROPERTY_TYPE);
        } else if (db.getXpath() != null) {
            Object result = db.getXpath().evaluate(itemsSchema);
            if (result instanceof PropertyDescriptor) {
                PropertyDescriptor pd = (PropertyDescriptor) result;
                binding = pd.getType().getBinding();
            }
        } else if (db.getCql() != null) {
            ExpressionTypeVisitor visitor = new ExpressionTypeVisitor(itemsSchema);
            binding = (Class<?>) db.getCql().accept(visitor, null);
        }
        // did we get a binding?
        if (binding != null) {
            return QueryablesBuilder.getSchema(binding);
        }
        // fall back for property not found or undetermined type
        return getGenericSchema();
    }

    private Schema getGenericSchema() {
        Schema schema = new Schema();
        schema.setType(AttributeType.STRING.getType());
        schema.setDescription(AttributeType.STRING.getType());
        return schema;
    }
}
