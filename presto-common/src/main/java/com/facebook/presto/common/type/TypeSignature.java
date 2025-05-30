/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.common.type;

import com.facebook.drift.annotations.ThriftConstructor;
import com.facebook.drift.annotations.ThriftField;
import com.facebook.drift.annotations.ThriftStruct;
import com.facebook.presto.common.InvalidTypeDefinitionException;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.type.BigintEnumType.LongEnumMap;
import com.facebook.presto.common.type.VarcharEnumType.VarcharEnumMap;
import com.facebook.presto.common.type.encoding.Base32;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.facebook.presto.common.type.StandardTypes.BIGINT_ENUM;
import static java.lang.Boolean.parseBoolean;
import static java.lang.Character.isDigit;
import static java.lang.Integer.min;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;
import static java.util.Locale.ENGLISH;

@ThriftStruct
public class TypeSignature
{
    private final TypeSignatureBase base;
    private final List<TypeSignatureParameter> parameters;
    private final boolean calculated;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_]([a-zA-Z0-9_:@])*");
    private static final Map<String, String> BASE_NAME_ALIAS_TO_CANONICAL =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> SIMPLE_TYPE_WITH_SPACES =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private static final String BIGINT_ENUM_PREFIX = BIGINT_ENUM.toLowerCase(ENGLISH);
    private static final Pattern ENUM_PREFIX = Pattern.compile("(varchar|bigint)enum\\(");
    private static final Pattern DISTINCT_TYPE_PREFIX = Pattern.compile("distincttype\\(");

    static {
        BASE_NAME_ALIAS_TO_CANONICAL.put("int", StandardTypes.INTEGER);

        SIMPLE_TYPE_WITH_SPACES.add(StandardTypes.TIME_WITH_TIME_ZONE);
        SIMPLE_TYPE_WITH_SPACES.add(StandardTypes.TIMESTAMP_WITH_TIME_ZONE);
        SIMPLE_TYPE_WITH_SPACES.add(StandardTypes.INTERVAL_DAY_TO_SECOND);
        SIMPLE_TYPE_WITH_SPACES.add(StandardTypes.INTERVAL_YEAR_TO_MONTH);
        SIMPLE_TYPE_WITH_SPACES.add("double precision");
    }

    public TypeSignature(UserDefinedType userDefinedType)
    {
        this(TypeSignatureBase.of(userDefinedType), userDefinedType.getPhysicalTypeSignature().getParameters());
    }

    public TypeSignature(DistinctTypeInfo distinctTypeInfo)
    {
        this(TypeSignatureBase.of(distinctTypeInfo), singletonList(TypeSignatureParameter.of(distinctTypeInfo)));
    }

    public TypeSignature(QualifiedObjectName base)
    {
        this(TypeSignatureBase.of(base), emptyList());
    }

    public TypeSignature(String base, TypeSignatureParameter... parameters)
    {
        this(base, asList(parameters));
    }

    public TypeSignature(String base, List<TypeSignatureParameter> parameters)
    {
        this(TypeSignatureBase.of(base), parameters);
    }

    public TypeSignature(TypeSignatureBase typeSignatureBase, List<TypeSignatureParameter> parameters)
    {
        this.base = typeSignatureBase;
        checkArgument(parameters != null, "parameters is null");
        this.parameters = unmodifiableList(new ArrayList<>(parameters));

        this.calculated = parameters.stream().anyMatch(TypeSignatureParameter::isCalculated);
    }

    // Add a ignore field to avoid construct conflict
    @ThriftConstructor
    public TypeSignature(String signature, boolean ignore)
    {
        this(parseTypeSignature(signature).getTypeSignatureBase(), parseTypeSignature(signature).getParameters());
    }

    public TypeSignature getStandardTypeSignature()
    {
        return new TypeSignature(base.getStandardTypeBase(), parameters);
    }

    public TypeSignatureBase getTypeSignatureBase()
    {
        return base;
    }

    public String getBase()
    {
        return base.toString();
    }

    public List<TypeSignatureParameter> getParameters()
    {
        return parameters;
    }

    public List<TypeSignature> getTypeOrNamedTypeParametersAsTypeSignatures()
    {
        List<TypeSignature> result = new ArrayList<>();
        for (TypeSignatureParameter parameter : parameters) {
            switch (parameter.getKind()) {
                case TYPE:
                    result.add(parameter.getTypeSignature());
                    break;
                case NAMED_TYPE:
                    result.add(parameter.getNamedTypeSignature().getTypeSignature());
                    break;
                default:
                    throw new IllegalStateException(
                            format("Expected all parameters to be of kind TYPE or NAMED_TYPE but [%s] kind was found for parameter: [%s]",
                                    parameter.getKind(), parameter));
            }
        }
        return unmodifiableList(result);
    }

    public boolean isCalculated()
    {
        return calculated;
    }

    public boolean isEnum()
    {
        return isBigintEnum() || isVarcharEnum();
    }

    public boolean isFunction()
    {
        return base.getStandardTypeBase().equals("function");
    }

    public boolean isBigintEnum()
    {
        return parameters.size() == 1 && parameters.get(0).isLongEnum();
    }

    public boolean isVarcharEnum()
    {
        return parameters.size() == 1 && parameters.get(0).isVarcharEnum();
    }

    public boolean isDistinctType()
    {
        return parameters.size() == 1 && parameters.get(0).isDistinctType();
    }

    public DistinctTypeInfo getDistinctTypeInfo()
    {
        checkArgument(isDistinctType(), format("%s is not a distinct type", this));
        return getParameters().get(0).getDistinctTypeInfo();
    }

    @JsonCreator
    public static TypeSignature parseTypeSignature(String signature)
    {
        return parseTypeSignature(signature, new HashSet<>());
    }

    public static TypeSignature parseTypeSignature(String signature, Set<String> literalCalculationParameters)
    {
        int posOfLessThan = signature.indexOf("<");
        int posOfParent = signature.indexOf("(");
        int posOfColon = signature.indexOf(":");
        if (posOfLessThan < 0 && posOfParent < 0 && posOfColon < 0) {
            // non-parametric standard type
            if (signature.equalsIgnoreCase(StandardTypes.VARCHAR)) {
                return VarcharType.createUnboundedVarcharType().getTypeSignature();
            }
            checkArgument(!literalCalculationParameters.contains(signature), "Bad type signature: '%s'", signature);
            return new TypeSignature(canonicalizeBaseName(signature), new ArrayList<>());
        }

        String lowerCaseSignature = signature.toLowerCase(ENGLISH);
        if (posOfColon > 0) {
            if (posOfLessThan < 0 && posOfParent < 0) {
                // unresolved named type: catalog.schema.type
                return new TypeSignature(canonicalizeBaseName(signature));
            }
            int startOfParams = min(posOfLessThan < 0 ? Integer.MAX_VALUE : posOfLessThan, posOfParent < 0 ? Integer.MAX_VALUE : posOfParent);
            if (posOfColon < startOfParams) {
                // resolved name type: catalog.schema.type:basetype
                return new TypeSignature(signature.substring(0, startOfParams), parseTypeSignature(signature.substring(posOfColon + 1)).getParameters());
            }
        }
        if (lowerCaseSignature.startsWith(StandardTypes.ROW + "(")) {
            return parseRowTypeSignature(signature, literalCalculationParameters);
        }

        Set<Integer> enumMapStartIndices = findEnumMapStartIndices(lowerCaseSignature);
        Set<Integer> distinctTypeStartIndices = findDistinctTypeStartIndices(lowerCaseSignature);
        Map<Integer, EnumMapParsingData> parsedEnumMaps = new HashMap<>();
        Map<Integer, DistinctTypeParsingData> parsedDistinctTypes = new HashMap<>();

        String baseName = null;
        List<TypeSignatureParameter> parameters = new ArrayList<>();
        int parameterStart = -1;
        int bracketCount = 0;
        int parameterEnd = -1;

        for (int i = 0; i < signature.length(); i++) {
            if (i < parameterEnd) {
                continue;
            }
            char c = signature.charAt(i);
            // TODO: remove angle brackets support once ROW<TYPE>(name) will be dropped
            // Angle brackets here are checked not for the support of ARRAY<> and MAP<>
            // but to correctly parse ARRAY(row<BIGINT, BIGINT>('a','b'))
            if (c == '(' || c == '<') {
                if (bracketCount == 0) {
                    verify(baseName == null, "Expected baseName to be null");
                    verify(parameterStart == -1, "Expected parameter start to be -1");
                    baseName = canonicalizeBaseName(signature.substring(0, i));
                    checkArgument(!literalCalculationParameters.contains(baseName), "Bad type signature: '%s'", signature);
                    parameterStart = i + 1;
                }
                bracketCount++;
            }
            else if (c == ')' || c == '>') {
                bracketCount--;
                checkArgument(bracketCount >= 0, "Bad type signature: '%s'", signature);
                if (bracketCount == 0) {
                    checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                    parameters.add(parseTypeSignatureParameter(signature, parameterStart, i, literalCalculationParameters, parsedEnumMaps, parsedDistinctTypes));
                    parameterStart = i + 1;
                    if (i == signature.length() - 1) {
                        return new TypeSignature(baseName, parameters);
                    }
                }
            }
            else if (enumMapStartIndices.contains(i)) {
                EnumMapParsingData enumMapParsingData = parseEnumMap(lowerCaseSignature, i);
                parameterEnd = enumMapParsingData.mapEndIndex;
                parsedEnumMaps.put(i, enumMapParsingData);
            }
            else if (distinctTypeStartIndices.contains(i)) {
                DistinctTypeParsingData distinctTypeParsingData = DistinctTypeParsingData.parse(lowerCaseSignature, i);
                parameterEnd = distinctTypeParsingData.endIndex;
                parsedDistinctTypes.put(i, distinctTypeParsingData);
            }
            else if (c == ',') {
                if (bracketCount == 1) {
                    checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                    parameters.add(parseTypeSignatureParameter(signature, parameterStart, i, literalCalculationParameters, parsedEnumMaps, parsedDistinctTypes));
                    parameterStart = i + 1;
                }
            }
        }

        throw new IllegalArgumentException(format("Bad type signature: '%s'", signature));
    }

    private static class DistinctTypeParsingData
    {
        private final int endIndex;
        private final DistinctTypeInfo distinctType;

        private DistinctTypeParsingData(int endIndex, DistinctTypeInfo distinctType)
        {
            this.endIndex = endIndex;
            this.distinctType = distinctType;
        }

        private static Optional<QualifiedObjectName> parseParentName(String s)
        {
            return s.equals("null") ? Optional.empty() : Optional.of(QualifiedObjectName.valueOf(s));
        }

        private static DistinctTypeParsingData parse(String signature, int startIndex)
        {
            int openBracketIndex = signature.indexOf("{", startIndex);
            if (openBracketIndex == -1) {
                throw new IllegalStateException(format("Cannot parse distinct type definition(%s), expected '{' after position %s", signature, startIndex));
            }
            QualifiedObjectName name = QualifiedObjectName.valueOf(signature.substring(startIndex, openBracketIndex));

            int firstCommaIndex = signature.indexOf(", ", openBracketIndex);
            if (firstCommaIndex == -1) {
                throw new IllegalStateException(format("Cannot parse distinct type definition(%s), expected ',' after position %s", signature, openBracketIndex));
            }
            TypeSignature baseType = TypeSignature.parseTypeSignature(signature.substring(openBracketIndex + 1, firstCommaIndex));

            int secondCommaIndex = signature.indexOf(", ", firstCommaIndex + 2);
            if (secondCommaIndex == -1) {
                throw new IllegalStateException(format("Cannot parse distinct type definition(%s), expected ',' after position %s", signature, secondCommaIndex));
            }
            boolean isOrderable = parseBoolean(signature.substring(firstCommaIndex + 2, secondCommaIndex));

            int thirdCommaIndex = signature.indexOf(", [", secondCommaIndex + 2);
            if (thirdCommaIndex == -1) {
                throw new IllegalStateException(format("Cannot parse distinct type definition(%s), expected '[' after position %s", signature, secondCommaIndex));
            }
            Optional<QualifiedObjectName> topMostAncestor = parseParentName(signature.substring(secondCommaIndex + 2, thirdCommaIndex));

            int endIndex = signature.indexOf("]}", thirdCommaIndex + 3);
            int position = thirdCommaIndex + 3;
            List<QualifiedObjectName> otherAncestors = new ArrayList<>();

            while (position < endIndex) {
                int nextPositionIndex = signature.indexOf(", ", position);
                if (nextPositionIndex == -1 || nextPositionIndex > endIndex) {
                    nextPositionIndex = endIndex;
                }
                otherAncestors.add(parseParentName(signature.substring(position, nextPositionIndex)).get());
                position = nextPositionIndex + 2;
            }

            return new DistinctTypeParsingData(endIndex + 1, new DistinctTypeInfo(name, baseType, topMostAncestor, otherAncestors, isOrderable));
        }
    }

    private static class EnumMapParsingData
    {
        final int mapEndIndex;
        private final String typeName;
        private final Map<String, String> map;
        private final boolean isBigintEnum;

        EnumMapParsingData(int mapEndIndex, String typeName, Map<String, String> map, boolean isBigintEnum)
        {
            this.mapEndIndex = mapEndIndex;
            this.typeName = typeName;
            this.map = map;
            this.isBigintEnum = isBigintEnum;
        }

        LongEnumMap getLongEnumMap()
        {
            checkArgument(isBigintEnum, "Invalid enum map format");
            return new LongEnumMap(
                    typeName,
                    map.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> Long.parseLong(e.getValue()))));
        }

        VarcharEnumMap getVarcharEnumMap()
        {
            checkArgument(!isBigintEnum, "Invalid enum map format");
            // Varchar enum values are base32-encoded so that they are case-insensitive, which is expected of TypeSignatures
            Base32 base32 = new Base32();
            return new VarcharEnumMap(
                    typeName,
                    map.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> new String(base32.decode(e.getValue().toUpperCase(ENGLISH))))));
        }
    }

    private static Set<Integer> findEnumMapStartIndices(String signature)
    {
        Set<Integer> indices = new HashSet<>();
        Matcher enumMatcher = ENUM_PREFIX.matcher(signature);
        while (enumMatcher.find()) {
            indices.add(enumMatcher.end());
        }
        return indices;
    }

    private static Set<Integer> findDistinctTypeStartIndices(String signature)
    {
        Set<Integer> indices = new HashSet<>();
        Matcher enumMatcher = DISTINCT_TYPE_PREFIX.matcher(signature);
        while (enumMatcher.find()) {
            indices.add(enumMatcher.end());
        }
        return indices;
    }

    private enum EnumMapParsingState
    {
        EXPECT_KEY,
        IN_KEY,
        IN_KEY_ESCAPE,
        EXPECT_COLON,
        EXPECT_VALUE,
        IN_NUM_VALUE,
        IN_STR_VALUE,
        IN_STR_VALUE_ESCAPE,
        EXPECT_COMMA_OR_CLOSING_BRACKET
    }

    private static EnumMapParsingData parseEnumMap(String signature, int startIndex)
    {
        EnumMapParsingState state = EnumMapParsingState.EXPECT_KEY;
        boolean isBigintEnum = signature.startsWith(BIGINT_ENUM_PREFIX, startIndex - BIGINT_ENUM_PREFIX.length() - 1);
        int openBracketIndex = signature.indexOf("{", startIndex);
        String typeName = signature.substring(startIndex, openBracketIndex);
        String key = null;
        StringBuilder keyOrValue = new StringBuilder();
        Map<String, String> map = new HashMap<>();

        for (int i = openBracketIndex + 1; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (state == EnumMapParsingState.IN_KEY_ESCAPE) {
                state = EnumMapParsingState.IN_KEY;
                keyOrValue.append(c);
            }
            if (state == EnumMapParsingState.IN_STR_VALUE_ESCAPE) {
                state = EnumMapParsingState.IN_STR_VALUE;
                keyOrValue.append(c);
            }
            else if (c == '"') {
                if (state == EnumMapParsingState.EXPECT_KEY) {
                    state = EnumMapParsingState.IN_KEY;
                }
                else if (state == EnumMapParsingState.EXPECT_VALUE) {
                    if (isBigintEnum) {
                        throw new IllegalStateException("Unexpected varchar value in numeric enum signature");
                    }
                    state = EnumMapParsingState.IN_STR_VALUE;
                }
                else if ((state == EnumMapParsingState.IN_KEY || state == EnumMapParsingState.IN_STR_VALUE)
                        && i + 1 < signature.length() && signature.charAt(i + 1) == '"') {
                    state = state == EnumMapParsingState.IN_KEY ? EnumMapParsingState.IN_KEY_ESCAPE : EnumMapParsingState.IN_STR_VALUE_ESCAPE;
                }
                else if (state == EnumMapParsingState.IN_KEY) {
                    state = EnumMapParsingState.EXPECT_COLON;
                }
                else if (state == EnumMapParsingState.IN_STR_VALUE) {
                    state = EnumMapParsingState.EXPECT_COMMA_OR_CLOSING_BRACKET;
                }
                else {
                    throw new IllegalStateException("Cannot parse enum signature");
                }
            }
            else if (state == EnumMapParsingState.IN_KEY || state == EnumMapParsingState.IN_STR_VALUE) {
                keyOrValue.append(c);
            }
            else if (c == ':' && state == EnumMapParsingState.EXPECT_COLON) {
                key = keyOrValue.toString();
                keyOrValue = new StringBuilder();
                state = EnumMapParsingState.EXPECT_VALUE;
            }
            else if ((Character.isDigit(c) || c == '-') && state == EnumMapParsingState.EXPECT_VALUE) {
                if (!isBigintEnum) {
                    throw new IllegalStateException("Unexpected numeric value in varchar enum signature");
                }
                state = EnumMapParsingState.IN_NUM_VALUE;
                keyOrValue.append(c);
            }
            else if (Character.isDigit(c) && state == EnumMapParsingState.IN_NUM_VALUE) {
                keyOrValue.append(c);
            }
            else if ((c == ',' || c == '}') && (state == EnumMapParsingState.EXPECT_COMMA_OR_CLOSING_BRACKET || state == EnumMapParsingState.IN_NUM_VALUE)) {
                if (key == null) {
                    throw new IllegalStateException("Cannot parse enum signature");
                }
                map.put(key, keyOrValue.toString());
                if (c == '}') {
                    return new EnumMapParsingData(i, typeName, map, isBigintEnum);
                }
                key = null;
                keyOrValue = new StringBuilder();
                state = EnumMapParsingState.EXPECT_KEY;
            }
            else if (!Character.isWhitespace(c)) {
                throw new IllegalStateException("Cannot parse enum signature");
            }
        }
        throw new IllegalStateException("Cannot parse enum signature");
    }

    private enum RowTypeSignatureParsingState
    {
        START_OF_FIELD,
        DELIMITED_NAME,
        DELIMITED_NAME_ESCAPED,
        TYPE_OR_NAMED_TYPE,
        TYPE,
        FINISHED,
    }

    private static TypeSignature parseRowTypeSignature(String signature, Set<String> literalParameters)
    {
        checkArgument(signature.toLowerCase(ENGLISH).startsWith(StandardTypes.ROW + "("), "Not a row type signature: '%s'", signature);

        RowTypeSignatureParsingState state = RowTypeSignatureParsingState.START_OF_FIELD;
        int bracketLevel = 1;
        int tokenStart = -1;
        String delimitedColumnName = null;

        List<TypeSignatureParameter> fields = new ArrayList<>();

        Set<String> distinctFieldNames = new HashSet<>();
        for (int i = StandardTypes.ROW.length() + 1; i < signature.length(); i++) {
            char c = signature.charAt(i);
            switch (state) {
                case START_OF_FIELD:
                    if (c == '"') {
                        state = RowTypeSignatureParsingState.DELIMITED_NAME;
                        tokenStart = i;
                    }
                    else if (isValidStartOfIdentifier(c)) {
                        state = RowTypeSignatureParsingState.TYPE_OR_NAMED_TYPE;
                        tokenStart = i;
                    }
                    else {
                        checkArgument(c == ' ', "Bad type signature: '%s'", signature);
                    }
                    break;

                case DELIMITED_NAME:
                    if (c == '"') {
                        if (i + 1 < signature.length() && signature.charAt(i + 1) == '"') {
                            state = RowTypeSignatureParsingState.DELIMITED_NAME_ESCAPED;
                        }
                        else {
                            // Remove quotes around the delimited column name
                            verify(tokenStart >= 0, "Expect tokenStart to be non-negative");
                            delimitedColumnName = signature.substring(tokenStart + 1, i);
                            tokenStart = i + 1;
                            state = RowTypeSignatureParsingState.TYPE;
                        }
                    }
                    break;

                case DELIMITED_NAME_ESCAPED:
                    verify(c == '"', "Expect quote after escape");
                    state = RowTypeSignatureParsingState.DELIMITED_NAME;
                    break;

                case TYPE_OR_NAMED_TYPE:
                    if (c == '(') {
                        bracketLevel++;
                    }
                    else if (c == ')' && bracketLevel > 1) {
                        bracketLevel--;
                    }
                    else if (c == ')') {
                        verify(tokenStart >= 0, "Expect tokenStart to be non-negative");
                        TypeSignatureParameter parameter = parseTypeOrNamedType(signature.substring(tokenStart, i).trim(), literalParameters);
                        parameter.getNamedTypeSignature().getName()
                                .ifPresent(fieldName -> checkDuplicateAndAdd(distinctFieldNames, fieldName));
                        fields.add(parameter);
                        tokenStart = -1;
                        state = RowTypeSignatureParsingState.FINISHED;
                    }
                    else if (c == ',' && bracketLevel == 1) {
                        verify(tokenStart >= 0, "Expect tokenStart to be non-negative");
                        TypeSignatureParameter parameter = parseTypeOrNamedType(signature.substring(tokenStart, i).trim(), literalParameters);
                        parameter.getNamedTypeSignature().getName()
                                .ifPresent(fieldName -> checkDuplicateAndAdd(distinctFieldNames, fieldName));
                        fields.add(parameter);
                        tokenStart = -1;
                        state = RowTypeSignatureParsingState.START_OF_FIELD;
                    }
                    break;

                case TYPE:
                    if (c == '(') {
                        bracketLevel++;
                    }
                    else if (c == ')' && bracketLevel > 1) {
                        bracketLevel--;
                    }
                    else if (c == ')') {
                        verify(tokenStart >= 0, "Expect tokenStart to be non-negative");
                        verify(delimitedColumnName != null, "Expect delimitedColumnName to be non-null");
                        checkDuplicateAndAdd(distinctFieldNames, delimitedColumnName);
                        fields.add(TypeSignatureParameter.of(new NamedTypeSignature(
                                Optional.of(new RowFieldName(delimitedColumnName, true)),
                                parseTypeSignature(signature.substring(tokenStart, i).trim(), literalParameters))));
                        delimitedColumnName = null;
                        tokenStart = -1;
                        state = RowTypeSignatureParsingState.FINISHED;
                    }
                    else if (c == ',' && bracketLevel == 1) {
                        verify(tokenStart >= 0, "Expect tokenStart to be non-negative");
                        verify(delimitedColumnName != null, "Expect delimitedColumnName to be non-null");
                        checkDuplicateAndAdd(distinctFieldNames, delimitedColumnName);
                        fields.add(TypeSignatureParameter.of(new NamedTypeSignature(
                                Optional.of(new RowFieldName(delimitedColumnName, true)),
                                parseTypeSignature(signature.substring(tokenStart, i).trim(), literalParameters))));
                        delimitedColumnName = null;
                        tokenStart = -1;
                        state = RowTypeSignatureParsingState.START_OF_FIELD;
                    }
                    break;

                case FINISHED:
                    throw new IllegalStateException(format("Bad type signature: '%s'", signature));

                default:
                    throw new AssertionError(format("Unexpected RowTypeSignatureParsingState: %s", state));
            }
        }

        checkArgument(state == RowTypeSignatureParsingState.FINISHED, "Bad type signature: '%s'", signature);
        return new TypeSignature(signature.substring(0, StandardTypes.ROW.length()), fields);
    }

    private static void checkDuplicateAndAdd(Set<String> fieldNames, String fieldName)
    {
        if (!fieldNames.add(fieldName)) {
            throw new InvalidTypeDefinitionException("Duplicate field: " + fieldName);
        }
    }

    private static TypeSignatureParameter parseTypeOrNamedType(String typeOrNamedType, Set<String> literalParameters)
    {
        int split = typeOrNamedType.indexOf(' ');

        // Type without space or simple type with spaces
        if (split == -1 || SIMPLE_TYPE_WITH_SPACES.contains(typeOrNamedType)) {
            return TypeSignatureParameter.of(new NamedTypeSignature(Optional.empty(), parseTypeSignature(typeOrNamedType, literalParameters)));
        }

        // Assume the first part of a structured type always has non-alphabetical character.
        // If the first part is a valid identifier, parameter is a named field.
        String firstPart = typeOrNamedType.substring(0, split);
        if (IDENTIFIER_PATTERN.matcher(firstPart).matches()) {
            return TypeSignatureParameter.of(new NamedTypeSignature(
                    Optional.of(new RowFieldName(firstPart, false)),
                    parseTypeSignature(typeOrNamedType.substring(split + 1).trim(), literalParameters)));
        }

        // Structured type composed from types with spaces. i.e. array(timestamp with time zone)
        return TypeSignatureParameter.of(new NamedTypeSignature(Optional.empty(), parseTypeSignature(typeOrNamedType, literalParameters)));
    }

    private static TypeSignatureParameter parseTypeSignatureParameter(
            String signature,
            int begin,
            int end,
            Set<String> literalCalculationParameters,
            Map<Integer, EnumMapParsingData> parsedEnumMaps,
            Map<Integer, DistinctTypeParsingData> parsedDistinctTypes)
    {
        String parameterName = signature.substring(begin, end).trim();
        if (isDigit(parameterName.charAt(0))) {
            return TypeSignatureParameter.of(Long.parseLong(parameterName));
        }
        else if (literalCalculationParameters.contains(parameterName)) {
            return TypeSignatureParameter.of(parameterName);
        }
        else if (parsedEnumMaps.containsKey(begin)) {
            if (!parameterName.endsWith("}")) {
                throw new IllegalStateException("Cannot parse enum signature");
            }
            EnumMapParsingData enumMapData = parsedEnumMaps.get(begin);
            if (enumMapData.isBigintEnum) {
                return TypeSignatureParameter.of(enumMapData.getLongEnumMap());
            }
            return TypeSignatureParameter.of(enumMapData.getVarcharEnumMap());
        }
        else if (parsedDistinctTypes.containsKey(begin)) {
            if (!parameterName.endsWith("}")) {
                throw new IllegalStateException(format("Cannot parse distinct type signature (%s), doesn't end with '}'", parameterName));
            }
            return TypeSignatureParameter.of(parsedDistinctTypes.get(begin).distinctType);
        }
        else {
            return TypeSignatureParameter.of(parseTypeSignature(parameterName, literalCalculationParameters));
        }
    }

    private static boolean isValidStartOfIdentifier(char c)
    {
        return (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                c == '_';
    }

    @Override
    @JsonValue
    @ThriftField(value = 1, name = "signature")
    public String toString()
    {
        String baseString = base.toString();
        if (parameters.isEmpty()) {
            return baseString;
        }

        if (baseString.equalsIgnoreCase(StandardTypes.VARCHAR) &&
                (parameters.size() == 1) &&
                parameters.get(0).isLongLiteral() &&
                parameters.get(0).getLongLiteral() == VarcharType.UNBOUNDED_LENGTH) {
            return baseString;
        }

        StringBuilder typeName = new StringBuilder(baseString);
        typeName.append("(").append(parameters.get(0));
        for (int i = 1; i < parameters.size(); i++) {
            typeName.append(",").append(parameters.get(i));
        }
        typeName.append(")");
        return typeName.toString();
    }

    @ThriftField(2)
    public boolean getIgnore()
    {
        return true;
    }

    private static void checkArgument(boolean argument, String format, Object... args)
    {
        if (!argument) {
            throw new IllegalArgumentException(format(format, args));
        }
    }

    private static void verify(boolean argument, String message)
    {
        if (!argument) {
            throw new AssertionError(message);
        }
    }

    private static String canonicalizeBaseName(String baseName)
    {
        String canonicalBaseName = BASE_NAME_ALIAS_TO_CANONICAL.get(baseName);
        if (canonicalBaseName == null) {
            return baseName;
        }
        return canonicalBaseName;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        TypeSignature other = (TypeSignature) o;

        return Objects.equals(this.base, other.base) &&
                Objects.equals(this.parameters, other.parameters);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(base, parameters);
    }
}
