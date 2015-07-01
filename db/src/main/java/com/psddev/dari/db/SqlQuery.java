package com.psddev.dari.db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import com.psddev.dari.util.CompactMap;
import com.psddev.dari.util.ObjectUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JoinType;
import org.jooq.RenderContext;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

/** Internal representation of an SQL query based on a Dari one. */
class SqlQuery {

    public static final String COUNT_ALIAS = "_count";

    private static final Pattern QUERY_KEY_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    //private static final Logger LOGGER = LoggerFactory.getLogger(SqlQuery.class);

    protected final SqlDatabase database;
    protected final Query<?> query;
    protected final String aliasPrefix;

    protected final SqlVendor vendor;
    private final DSLContext dslContext;
    private final RenderContext tableRenderContext;
    protected final RenderContext renderContext;
    private final Table<?> recordTable;
    protected final Field<UUID> recordIdField;
    protected final Field<UUID> recordTypeIdField;
    protected final Map<String, Query.MappedKey> mappedKeys;
    protected final Map<String, ObjectIndex> selectedIndexes;

    private String fromClause;
    private Condition whereCondition;
    private Condition havingCondition;
    private final List<SortField<?>> orderByFields = new ArrayList<>();
    protected final List<SqlQueryJoin> joins = new ArrayList<>();
    private final Map<Query<?>, String> subQueries = new CompactMap<>();
    private final Map<Query<?>, SqlQuery> subSqlQueries = new HashMap<>();

    private boolean needsDistinct;
    protected SqlQueryJoin mysqlIndexHint;
    private boolean mysqlIgnoreIndexPrimaryDisabled;
    private boolean forceLeftJoins;

    /**
     * Creates an instance that can translate the given {@code query}
     * with the given {@code database}.
     */
    public SqlQuery(
            SqlDatabase initialDatabase,
            Query<?> initialQuery,
            String initialAliasPrefix) {

        database = initialDatabase;
        query = initialQuery;
        aliasPrefix = initialAliasPrefix;

        vendor = database.getVendor();
        dslContext = DSL.using(SQLDialect.MYSQL);
        tableRenderContext = dslContext.renderContext().paramType(ParamType.INLINED).declareTables(true);
        renderContext = dslContext.renderContext().paramType(ParamType.INLINED);

        String recordTableAlias = aliasPrefix + "r";

        recordTable = DSL.table(DSL.name(SqlDatabase.RECORD_TABLE)).as(recordTableAlias);
        recordIdField = DSL.field(DSL.name(recordTableAlias, SqlDatabase.ID_COLUMN), vendor.uuidDataType());
        recordTypeIdField = DSL.field(DSL.name(recordTableAlias, SqlDatabase.TYPE_ID_COLUMN), vendor.uuidDataType());
        mappedKeys = query.mapEmbeddedKeys(database.getEnvironment());
        selectedIndexes = new HashMap<>();

        for (Map.Entry<String, Query.MappedKey> entry : mappedKeys.entrySet()) {
            selectIndex(entry.getKey(), entry.getValue());
        }
    }

    private void selectIndex(String queryKey, Query.MappedKey mappedKey) {
        ObjectIndex selectedIndex = null;
        int maxMatchCount = 0;

        for (ObjectIndex index : mappedKey.getIndexes()) {
            List<String> indexFields = index.getFields();
            int matchCount = 0;

            for (Query.MappedKey mk : mappedKeys.values()) {
                ObjectField mkf = mk.getField();
                if (mkf != null && indexFields.contains(mkf.getInternalName())) {
                    ++ matchCount;
                }
            }

            if (matchCount > maxMatchCount) {
                selectedIndex = index;
                maxMatchCount = matchCount;
            }
        }

        if (selectedIndex != null) {
            if (maxMatchCount == 1) {
                for (ObjectIndex index : mappedKey.getIndexes()) {
                    if (index.getFields().size() == 1) {
                        selectedIndex = index;
                        break;
                    }
                }
            }

            selectedIndexes.put(queryKey, selectedIndex);
        }
    }

    public SqlQuery(SqlDatabase initialDatabase, Query<?> initialQuery) {
        this(initialDatabase, initialQuery, "");
    }

    protected Field<Object> aliasedField(String alias, String field) {
        return field != null ? DSL.field(DSL.name(aliasPrefix + alias, field)) : null;
    }

    private SqlQuery getOrCreateSubSqlQuery(Query<?> subQuery, boolean forceLeftJoins) {
        SqlQuery subSqlQuery = subSqlQueries.get(subQuery);
        if (subSqlQuery == null) {
            subSqlQuery = new SqlQuery(database, subQuery, aliasPrefix + "s" + subSqlQueries.size());
            subSqlQuery.forceLeftJoins = forceLeftJoins;
            subSqlQuery.initializeClauses();
            subSqlQueries.put(subQuery, subSqlQuery);
        }
        return subSqlQuery;
    }

    /** Initializes FROM, WHERE, and ORDER BY clauses. */
    private void initializeClauses() {
        String extraJoins = ObjectUtils.to(String.class, query.getOptions().get(SqlDatabase.EXTRA_JOINS_QUERY_OPTION));

        if (extraJoins != null) {
            Matcher queryKeyMatcher = QUERY_KEY_PATTERN.matcher(extraJoins);
            int lastEnd = 0;
            StringBuilder newExtraJoinsBuilder = new StringBuilder();

            while (queryKeyMatcher.find()) {
                newExtraJoinsBuilder.append(extraJoins.substring(lastEnd, queryKeyMatcher.start()));
                lastEnd = queryKeyMatcher.end();

                String queryKey = queryKeyMatcher.group(1);
                Query.MappedKey mappedKey = query.mapEmbeddedKey(database.getEnvironment(), queryKey);
                mappedKeys.put(queryKey, mappedKey);
                selectIndex(queryKey, mappedKey);
                SqlQueryJoin join = SqlQueryJoin.findOrCreate(this, queryKey);
                join.type = JoinType.LEFT_OUTER_JOIN;
                newExtraJoinsBuilder.append(renderContext.render(join.getValueField(queryKey, null)));
            }

            newExtraJoinsBuilder.append(extraJoins.substring(lastEnd));
            extraJoins = newExtraJoinsBuilder.toString();
        }

        // Builds the WHERE clause.
        Condition whereCondition = query.isFromAll()
                ? DSL.trueCondition()
                : recordTypeIdField.in(query.getConcreteTypeIds(database));

        Predicate predicate = query.getPredicate();

        if (predicate != null) {
            Condition condition = createWhereCondition(predicate, null, false);

            if (condition != null) {
                whereCondition = whereCondition.and(condition);
            }
        }

        String extraWhere = ObjectUtils.to(String.class, query.getOptions().get(SqlDatabase.EXTRA_WHERE_QUERY_OPTION));

        if (!ObjectUtils.isBlank(extraWhere)) {
            whereCondition = whereCondition.and(extraWhere);
        }

        // Creates jOOQ SortField from Dari Sorter.
        for (Sorter sorter : query.getSorters()) {
            String operator = sorter.getOperator();
            boolean ascending = Sorter.ASCENDING_OPERATOR.equals(operator);
            boolean descending = Sorter.DESCENDING_OPERATOR.equals(operator);
            boolean closest = Sorter.CLOSEST_OPERATOR.equals(operator);
            boolean farthest = Sorter.FARTHEST_OPERATOR.equals(operator);

            if (!(ascending || descending || closest || farthest)) {
                throw new UnsupportedSorterException(database, sorter);
            }

            String queryKey = (String) sorter.getOptions().get(0);
            SqlQueryJoin join = SqlQueryJoin.findOrCreateForSort(this, queryKey);
            Field<?> joinValueField = join.getValueField(queryKey, null);
            Query<?> subQuery = mappedKeys.get(queryKey).getSubQueryWithSorter(sorter, 0);

            if (subQuery != null) {
                SqlQuery subSqlQuery = getOrCreateSubSqlQuery(subQuery, true);

                subQueries.put(subQuery, renderContext.render(joinValueField) + " = ");
                orderByFields.addAll(subSqlQuery.orderByFields);
                continue;
            }

            if (ascending) {
                orderByFields.add(joinValueField.sort(SortOrder.ASC));

            } else if (descending) {
                orderByFields.add(joinValueField.sort(SortOrder.DESC));

            } else {
                try {
                    Location location = (Location) sorter.getOptions().get(1);
                    StringBuilder selectBuilder = new StringBuilder();
                    StringBuilder locationFieldBuilder = new StringBuilder();

                    vendor.appendNearestLocation(locationFieldBuilder, selectBuilder, location, renderContext.render(joinValueField));

                    Field<?> locationField = DSL.field(locationFieldBuilder.toString());

                    if (closest) {
                        orderByFields.add(locationField.sort(SortOrder.ASC));

                    } else {
                        orderByFields.add(locationField.sort(SortOrder.DESC));
                    }

                } catch (UnsupportedIndexException uie) {
                    throw new UnsupportedIndexException(vendor, queryKey);
                }
            }
        }

        // Builds the FROM clause.
        StringBuilder fromBuilder = new StringBuilder();

        for (SqlQueryJoin join : joins) {
            if (join.indexKeys.isEmpty()) {
                continue;
            }

            // e.g. JOIN RecordIndex AS i#
            fromBuilder.append('\n');
            fromBuilder.append((forceLeftJoins ? JoinType.LEFT_OUTER_JOIN : join.type).toSQL());
            fromBuilder.append(' ');
            fromBuilder.append(tableRenderContext.render(join.table));

            if (join.type == JoinType.JOIN && join.equals(mysqlIndexHint)) {
                fromBuilder.append(" /*! USE INDEX (k_name_value) */");

            } else if (join.sqlIndex == SqlIndex.LOCATION
                    && join.sqlIndexTable.getVersion() >= 2) {

                fromBuilder.append(" /*! IGNORE INDEX (PRIMARY) */");
            }

            if ((join.sqlIndex == SqlIndex.LOCATION && join.sqlIndexTable.getVersion() < 3)
                    || (join.sqlIndex == SqlIndex.NUMBER && join.sqlIndexTable.getVersion() < 3)
                    || (join.sqlIndex == SqlIndex.STRING && join.sqlIndexTable.getVersion() < 4)
                    || (join.sqlIndex == SqlIndex.UUID && join.sqlIndexTable.getVersion() < 3)) {

                mysqlIgnoreIndexPrimaryDisabled = true;
            }

            // e.g. ON i#.recordId = r.id
            fromBuilder.append(" ON ");

            Condition joinCondition = join.idField.eq(recordIdField);

            // AND i#.typeId = r.typeId
            if (join.typeIdField != null) {
                joinCondition = joinCondition.and(join.typeIdField.eq(recordTypeIdField));
            }

            // AND i#.symbolId in (...)
            joinCondition = joinCondition.and(
                    join.keyField.in(
                            join.indexKeys.stream()
                                    .map(join::convertIndexKey)
                                    .collect(Collectors.toSet())));

            fromBuilder.append(renderContext.render(joinCondition));
        }

        for (Map.Entry<Query<?>, String> entry : subQueries.entrySet()) {
            Query<?> subQuery = entry.getKey();
            SqlQuery subSqlQuery = getOrCreateSubSqlQuery(subQuery, false);

            if (subSqlQuery.needsDistinct) {
                needsDistinct = true;
            }

            String alias = subSqlQuery.aliasPrefix + "r";

            fromBuilder.append("\nINNER JOIN ");
            fromBuilder.append(tableRenderContext.render(DSL.table(DSL.name(SqlDatabase.RECORD_TABLE)).as(alias)));
            fromBuilder.append(" ON ");
            fromBuilder.append(entry.getValue());
            fromBuilder.append(renderContext.render(DSL.field(DSL.name(alias, SqlDatabase.ID_COLUMN))));
            fromBuilder.append(subSqlQuery.fromClause);
        }

        if (extraJoins != null) {
            fromBuilder.append(' ');
            fromBuilder.append(extraJoins);
        }

        this.whereCondition = whereCondition;

        String extraHaving = ObjectUtils.to(String.class, query.getOptions().get(SqlDatabase.EXTRA_HAVING_QUERY_OPTION));

        this.havingCondition = !ObjectUtils.isBlank(extraHaving)
                ? DSL.condition(extraHaving)
                : null;

        this.fromClause = fromBuilder.toString();
    }

    // Creates jOOQ Condition from Dari Predicate.
    private Condition createWhereCondition(
            Predicate predicate,
            Predicate parentPredicate,
            boolean usesLeftJoin) {

        if (predicate instanceof CompoundPredicate) {
            CompoundPredicate compoundPredicate = (CompoundPredicate) predicate;
            String operator = compoundPredicate.getOperator();
            boolean isNot = PredicateParser.NOT_OPERATOR.equals(operator);

            // e.g. (child1) OR (child2) OR ... (child#)
            if (isNot || PredicateParser.OR_OPERATOR.equals(operator)) {
                List<Predicate> children = compoundPredicate.getChildren();
                boolean usesLeftJoinChildren;

                if (children.size() > 1) {
                    usesLeftJoinChildren = true;
                    needsDistinct = true;

                } else {
                    usesLeftJoinChildren = isNot;
                }

                Condition compoundCondition = null;

                for (Predicate child : children) {
                    Condition childCondition = createWhereCondition(child, predicate, usesLeftJoinChildren);

                    if (childCondition != null) {
                        compoundCondition = compoundCondition != null
                                ? compoundCondition.or(childCondition)
                                : childCondition;
                    }
                }

                return isNot && compoundCondition != null
                        ? compoundCondition.not()
                        : compoundCondition;

            // e.g. (child1) AND (child2) AND .... (child#)
            } else if (PredicateParser.AND_OPERATOR.equals(operator)) {
                Condition compoundCondition = null;

                for (Predicate child : compoundPredicate.getChildren()) {
                    Condition childCondition = createWhereCondition(child, predicate, usesLeftJoin);

                    if (childCondition != null) {
                        compoundCondition = compoundCondition != null
                                ? compoundCondition.and(childCondition)
                                : childCondition;
                    }
                }

                return compoundCondition;
            }

        } else if (predicate instanceof ComparisonPredicate) {
            ComparisonPredicate comparisonPredicate = (ComparisonPredicate) predicate;
            String queryKey = comparisonPredicate.getKey();
            Query.MappedKey mappedKey = mappedKeys.get(queryKey);
            boolean isFieldCollection = mappedKey.isInternalCollectionType();
            SqlQueryJoin join = null;

            if (mappedKey.getField() != null
                    && parentPredicate instanceof CompoundPredicate
                    && PredicateParser.OR_OPERATOR.equals(parentPredicate.getOperator())) {

                for (SqlQueryJoin j : joins) {
                    if (j.parent == parentPredicate
                            && j.sqlIndex.equals(SqlIndex.Static.getByType(mappedKeys.get(queryKey).getInternalType()))) {

                        needsDistinct = true;
                        join = j;

                        join.addIndexKey(queryKey);
                        break;
                    }
                }

                if (join == null) {
                    join = SqlQueryJoin.findOrCreate(this, queryKey);
                    join.parent = parentPredicate;
                }

            } else if (isFieldCollection) {
                join = SqlQueryJoin.create(this, queryKey);

            } else {
                join = SqlQueryJoin.findOrCreate(this, queryKey);
            }

            if (usesLeftJoin) {
                join.type = JoinType.LEFT_OUTER_JOIN;
            }

            if (isFieldCollection
                    && (join.sqlIndexTable == null
                    || join.sqlIndexTable.getVersion() < 2)) {

                needsDistinct = true;
            }

            Field<Object> joinValueField = join.getValueField(queryKey, comparisonPredicate);
            Query<?> valueQuery = mappedKey.getSubQueryWithComparison(comparisonPredicate);
            String operator = comparisonPredicate.getOperator();
            boolean isNotEqualsAll = PredicateParser.NOT_EQUALS_ALL_OPERATOR.equals(operator);

            // e.g. field IN (SELECT ...)
            if (valueQuery != null) {
                if (isNotEqualsAll || isFieldCollection) {
                    needsDistinct = true;
                }

                if (findSimilarComparison(mappedKey.getField(), query.getPredicate())) {
                    Table<?> subQueryTable = DSL.table(new SqlQuery(database, valueQuery).subQueryStatement());
                    Condition subQueryCondition = isNotEqualsAll
                            ? joinValueField.notIn(subQueryTable)
                            : joinValueField.in(subQueryTable);

                    return subQueryCondition;

                } else {
                    SqlQuery subSqlQuery = getOrCreateSubSqlQuery(valueQuery, join.type == JoinType.LEFT_OUTER_JOIN);

                    subQueries.put(valueQuery, renderContext.render(joinValueField) + (isNotEqualsAll ? " != " : " = "));
                    return subSqlQuery.whereCondition;
                }
            }

            List<Condition> comparisonConditions = new ArrayList<>();
            boolean hasMissing = false;

            if (isNotEqualsAll || PredicateParser.EQUALS_ANY_OPERATOR.equals(operator)) {
                for (Object value : comparisonPredicate.resolveValues(database)) {
                    if (value == null) {
                        comparisonConditions.add(DSL.falseCondition());

                    } else if (value == Query.MISSING_VALUE) {
                        hasMissing = true;

                        if (isNotEqualsAll) {
                            if (isFieldCollection) {
                                needsDistinct = true;
                            }

                            comparisonConditions.add(joinValueField.isNotNull());

                        } else {
                            join.type = JoinType.LEFT_OUTER_JOIN;

                            comparisonConditions.add(joinValueField.isNull());
                        }

                    } else if (value instanceof Region) {
                        List<Location> locations = ((Region) value).getLocations();

                        if (!locations.isEmpty()) {
                            try {
                                StringBuilder rcb = new StringBuilder();

                                vendor.appendWhereRegion(rcb, (Region) value, renderContext.render(joinValueField));

                                Condition rc = DSL.condition(rcb.toString());

                                if (isNotEqualsAll) {
                                    rc = rc.not();
                                }

                                comparisonConditions.add(rc);

                            } catch (UnsupportedIndexException uie) {
                                throw new UnsupportedIndexException(vendor, queryKey);
                            }
                        }

                    } else {
                        Object convertedValue = join.convertValue(comparisonPredicate, value);

                        if (isNotEqualsAll) {
                            join.type = JoinType.LEFT_OUTER_JOIN;
                            needsDistinct = true;
                            hasMissing = true;

                            comparisonConditions.add(
                                    joinValueField.isNull().or(
                                            joinValueField.ne(convertedValue)));

                        } else {
                            comparisonConditions.add(joinValueField.eq(convertedValue));
                        }
                    }
                }

            } else {
                SqlQueryComparison sqlQueryComparison = SqlQueryComparison.find(operator);

                // e.g. field OP value1 OR field OP value2 OR ... field OP value#
                if (sqlQueryComparison != null) {
                    for (Object value : comparisonPredicate.resolveValues(database)) {
                        if (value == null) {
                            comparisonConditions.add(DSL.falseCondition());

                        } else if (value instanceof Location) {
                            try {
                                StringBuilder lb = new StringBuilder();

                                vendor.appendWhereLocation(lb, (Location) value, renderContext.render(joinValueField));
                                comparisonConditions.add(DSL.condition(lb.toString()));

                            } catch (UnsupportedIndexException uie) {
                                throw new UnsupportedIndexException(vendor, queryKey);
                            }

                        } else if (value == Query.MISSING_VALUE) {
                            hasMissing = true;
                            join.type = JoinType.LEFT_OUTER_JOIN;

                            comparisonConditions.add(joinValueField.isNull());

                        } else {
                            comparisonConditions.add(
                                    sqlQueryComparison.createCondition(
                                            joinValueField,
                                            join.convertValue(comparisonPredicate, value)));
                        }
                    }
                }
            }

            if (comparisonConditions.isEmpty()) {
                return isNotEqualsAll ? DSL.trueCondition() : DSL.falseCondition();
            }

            Condition whereCondition = isNotEqualsAll
                    ? DSL.and(comparisonConditions)
                    : DSL.or(comparisonConditions);

            if (!hasMissing) {
                if (join.needsIndexTable) {
                    String indexKey = mappedKeys.get(queryKey).getIndexKey(selectedIndexes.get(queryKey));
                    if (indexKey != null) {
                        whereCondition = join.keyField.eq(join.convertIndexKey(indexKey)).and(whereCondition);
                    }
                }

                if (join.needsIsNotNull) {
                    whereCondition = joinValueField.isNotNull().and(whereCondition);
                }

                if (comparisonConditions.size() > 1) {
                    needsDistinct = true;
                }
            }

            return whereCondition;
        }

        throw new UnsupportedPredicateException(this, predicate);
    }

    private boolean findSimilarComparison(ObjectField field, Predicate predicate) {
        if (field != null) {
            if (predicate instanceof CompoundPredicate) {
                for (Predicate child : ((CompoundPredicate) predicate).getChildren()) {
                    if (findSimilarComparison(field, child)) {
                        return true;
                    }
                }

            } else if (predicate instanceof ComparisonPredicate) {
                ComparisonPredicate comparison = (ComparisonPredicate) predicate;
                Query.MappedKey mappedKey = mappedKeys.get(comparison.getKey());

                if (field.equals(mappedKey.getField())
                        && mappedKey.getSubQueryWithComparison(comparison) == null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns an SQL statement that can be used to get a count
     * of all rows matching the query.
     */
    public String countStatement() {
        initializeClauses();

        return renderContext.render(dslContext
                .select(needsDistinct ? recordIdField.countDistinct() : recordIdField.count())
                .from(DSL.table(tableRenderContext.render(recordTable)) + fromClause.replace(" /*! USE INDEX (k_name_value) */", ""))
                .where(whereCondition));
    }

    /**
     * Returns an SQL statement that can be used to delete all rows
     * matching the query.
     */
    public String deleteStatement() {
        initializeClauses();

        return renderContext.render(dslContext
                .deleteFrom(DSL.table(tableRenderContext.render(recordTable) + fromClause))
                .where(whereCondition));
    }

    /**
     * Returns an SQL statement that can be used to group rows by the values
     * of the given {@code groupKeys}.
     *
     * @param groupKeys Can't be {@code null} or empty.
     * @throws IllegalArgumentException If {@code groupKeys} is empty.
     * @throws NullPointerException If {@code groupKeys} is {@code null}.
     */
    public String groupStatement(String... groupKeys) {
        Preconditions.checkNotNull(groupKeys, "[groupKeys] can't be null!");
        Preconditions.checkArgument(groupKeys.length > 0, "[groupKeys] can't be empty!");

        List<Field<?>> groupByFields = new ArrayList<>();

        for (String groupKey : groupKeys) {
            Query.MappedKey mappedKey = query.mapEmbeddedKey(database.getEnvironment(), groupKey);

            mappedKeys.put(groupKey, mappedKey);
            selectIndex(groupKey, mappedKey);

            SqlQueryJoin join = SqlQueryJoin.findOrCreate(this, groupKey);
            Field<?> joinValueField = join.getValueField(groupKey, null);
            Query<?> subQuery = mappedKey.getSubQueryWithGroupBy();

            if (subQuery == null) {
                groupByFields.add(joinValueField);

            } else {
                SqlQuery subSqlQuery = getOrCreateSubSqlQuery(subQuery, true);

                subQueries.put(subQuery, renderContext.render(joinValueField) + " = ");
                subSqlQuery.joins.forEach(j -> groupByFields.add(j.getValueField(groupKey, null)));
            }
        }

        initializeClauses();

        List<Field<?>> selectFields = new ArrayList<>();

        selectFields.add((needsDistinct
                ? recordIdField.countDistinct()
                : recordIdField.count())
                .as(COUNT_ALIAS));

        selectFields.addAll(groupByFields);

        return renderContext.render(dslContext
                .select(selectFields)
                .from(DSL.table(tableRenderContext.render(recordTable) + fromClause.replace(" /*! USE INDEX (k_name_value) */", "")))
                .where(whereCondition)
                .groupBy(groupByFields)
                .having(havingCondition)
                .orderBy(orderByFields));
    }

    /**
     * Returns an SQL statement that can be used to get when the rows
     * matching the query were last updated.
     */
    public String lastUpdateStatement() {
        initializeClauses();

        String alias = aliasPrefix + "r";

        return renderContext.render(dslContext
                .select(DSL.field(DSL.name(alias, SqlDatabase.UPDATE_DATE_COLUMN)).max())
                .from(DSL.table(tableRenderContext.render(DSL.table(SqlDatabase.RECORD_UPDATE_TABLE).as(alias)) + fromClause))
                .where(whereCondition));
    }

    /**
     * Returns an SQL statement that can be used to list all rows
     * matching the query.
     */
    public String selectStatement() {
        initializeClauses();

        List<Field<?>> selectFields = new ArrayList<>();

        selectFields.add(recordIdField);
        selectFields.add(recordTypeIdField);

        List<String> queryFields = query.getFields();

        if (queryFields == null) {
            selectFields.add(DSL.field(DSL.name(aliasPrefix + "r", SqlDatabase.DATA_COLUMN)));

        } else if (!queryFields.isEmpty()) {
            queryFields.forEach(queryField -> selectFields.add(DSL.field(DSL.name(queryField))));
        }

        String extraColumns = ObjectUtils.to(String.class, query.getOptions().get(SqlDatabase.EXTRA_COLUMNS_QUERY_OPTION));

        if (!ObjectUtils.isBlank(extraColumns)) {
            for (String extraColumn : extraColumns.trim().split("\\s+")) {
                selectFields.add(DSL.field(DSL.name(extraColumn)));
            }
        }

        Table<?> selectTable = recordTable;

        if (fromClause.length() > 0
                && !fromClause.toLowerCase(Locale.ENGLISH).contains("left outer join")
                && !mysqlIgnoreIndexPrimaryDisabled) {

            selectTable = selectTable.ignoreIndex("PRIMARY");
        }

        Select<?> select = (needsDistinct
                ? dslContext.selectDistinct(recordIdField, recordTypeIdField)
                : dslContext.select(selectFields))
                .from(DSL.table(tableRenderContext.render(selectTable) + fromClause))
                .where(whereCondition)
                .having(havingCondition)
                .orderBy(orderByFields);

        if (needsDistinct && selectFields.size() > 2) {
            String distinctAlias = aliasPrefix + "d";
            select = dslContext
                    .select(selectFields)
                    .from(recordTable)
                    .join(select.asTable().as(distinctAlias))
                    .on(recordTypeIdField.eq(DSL.field(DSL.name(distinctAlias, SqlDatabase.TYPE_ID_COLUMN), vendor.uuidDataType())))
                    .and(recordIdField.eq(DSL.field(DSL.name(distinctAlias, SqlDatabase.ID_COLUMN), vendor.uuidDataType())));
        }

        return renderContext.render(select);
    }

    /**
     * Returns an SQL statement that can be used as a sub-query.
     */
    public String subQueryStatement() {
        initializeClauses();

        return renderContext.render((needsDistinct
                ? dslContext.selectDistinct(recordIdField)
                : dslContext.select(recordIdField))
                .from(DSL.table(tableRenderContext.render(recordTable) + fromClause))
                .where(whereCondition)
                .having(havingCondition)
                .orderBy(orderByFields));
    }
}
