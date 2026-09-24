/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0,
 * or the Eclipse Distribution License v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 */

package org.eclipse.persistence.internal.jpa.jpql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.Map;

import org.eclipse.persistence.descriptors.RelationalDescriptor;
import org.eclipse.persistence.internal.sessions.DatabaseSessionImpl;
import org.eclipse.persistence.platform.database.DatabasePlatform;
import org.eclipse.persistence.platform.database.MySQLPlatform;
import org.eclipse.persistence.platform.database.OraclePlatform;
import org.eclipse.persistence.platform.database.PostgreSQLPlatform;
import org.eclipse.persistence.queries.DatabaseQuery;
import org.eclipse.persistence.queries.ReportQuery;
import org.eclipse.persistence.sessions.DatabaseLogin;
import org.eclipse.persistence.tools.schemaframework.FieldDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CastExpressionPlatformTest {

    @ParameterizedTest
    @CsvSource({"String, VARCHAR", "Integer, INTEGER", "Long, BIGINT", "Float, FLOAT", "Double, FLOAT"})
    void standardCastsUsePostgreSQLTypes(String target, String sqlType) throws ClassNotFoundException {
        assertStandardCast(new PostgreSQLPlatform(), target, sqlType);
    }

    @ParameterizedTest
    @CsvSource({"String, VARCHAR2(4000 BYTE)", "Integer, NUMBER(10)", "Long, NUMBER(19)", "Float, NUMBER", "Double, NUMBER"})
    void standardCastsUseOracleTypes(String target, String sqlType) throws ClassNotFoundException {
        assertStandardCast(new OraclePlatform(), target, sqlType);
    }

    @ParameterizedTest
    @CsvSource({"String, CHAR", "Integer, SIGNED", "Long, SIGNED", "Float, FLOAT", "Double, DOUBLE"})
    void standardCastsUseMySQLTypes(String target, String sqlType) throws ClassNotFoundException {
        assertStandardCast(new MySQLPlatform(), target, sqlType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Double", "DOUBLE", "double"})
    void doubleCastUsesPostgreSQLType(String target) {
        // Cover both the reported numeric cast and the standard JPQL string cast.
        assertCastType(new PostgreSQLPlatform(), "numerator", target, "FLOAT");
        assertCastType(new PostgreSQLPlatform(), "text", target, "FLOAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Double", "DOUBLE", "double"})
    void doubleCastUsesOracleType(String target) {
        assertCastType(new OraclePlatform(), "numerator", target, "NUMBER");
        assertCastType(new OraclePlatform(), "text", target, "NUMBER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FLOAT8", "NUMERIC", "NUMERIC(10,2)", "CHAR(2)", "CustomType"})
    void explicitDatabaseTypesArePreserved(String target) {
        // The schema lookup can change explicit SQL types, for example NUMERIC to BIGINT.
        assertCastType(new PostgreSQLPlatform(), "text", target, target);
        assertCastType(new OraclePlatform(), "text", target, target);
    }

    @Test
    void doubleCastHonorsCustomPlatformMapping() {
        PostgreSQLPlatform platform = new PostgreSQLPlatform() {
            @Override
            protected Map<Class<?>, FieldDefinition.DatabaseType> buildDatabaseTypes() {
                Map<Class<?>, FieldDefinition.DatabaseType> types = super.buildDatabaseTypes();
                types.put(Double.class, new FieldDefinition.DatabaseType("FLOAT8", false));
                return types;
            }
        };
        assertCastType(platform, "numerator", "Double", "FLOAT8");
    }

    @Test
    void stringCastsUseNationalCharacterTypes() throws ClassNotFoundException {
        OraclePlatform oracle = new OraclePlatform();
        oracle.setUseNationalCharacterVaryingTypeForString(true);
        assertStandardCast(oracle, "String", "NVARCHAR2(2000)");
        MySQLPlatform mysql = new MySQLPlatform();
        mysql.setUseNationalCharacterVaryingTypeForString(true);
        assertStandardCast(mysql, "String", "NCHAR");
    }

    @Test
    void stringCastHonorsCustomPlatformMapping() throws ClassNotFoundException {
        PostgreSQLPlatform platform = new PostgreSQLPlatform() {
            @Override
            protected Map<Class<?>, FieldDefinition.DatabaseType> buildDatabaseTypes() {
                Map<Class<?>, FieldDefinition.DatabaseType> types = super.buildDatabaseTypes();
                types.put(String.class, new FieldDefinition.DatabaseType("TEXT", false));
                return types;
            }
        };
        assertStandardCast(platform, "String", "TEXT");
    }

    @Test
    void standardTargetNamesAreIndependentOfDefaultLocale() throws ClassNotFoundException {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertStandardCast(new PostgreSQLPlatform(), "Integer", "INTEGER");
            assertStandardCast(new PostgreSQLPlatform(), "String", "VARCHAR");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void nestedCastsRetainTheOuterResultType() {
        DatabaseSessionImpl session = newSession(new PostgreSQLPlatform());
        ReportQuery query = (ReportQuery) new HermesParser().buildQuery(
                "SELECT CAST(CAST(f.text AS Long) AS Double) FROM Fraction f", session);
        query.prepareCall(session, null);
        assertEquals("SELECT CAST(CAST(TEXT_VALUE AS BIGINT) AS FLOAT) FROM FRACTION", query.getSQLString());
        assertEquals(Double.class, query.getItems().get(0).getResultType());
    }

    private void assertStandardCast(DatabasePlatform platform, String target, String expectedType) throws ClassNotFoundException {
        Class<?> javaType = Class.forName("java.lang." + target);
        for (String spelling : new String[] {target, target.toUpperCase(Locale.ROOT), target.toLowerCase(Locale.ROOT)}) {
            DatabaseSessionImpl session = newSession(platform);
            String column = javaType == String.class ? "NUMERATOR" : "TEXT_VALUE";
            String field = javaType == String.class ? "numerator" : "text";
            ReportQuery query = (ReportQuery) new HermesParser().buildQuery(
                    "SELECT CAST(f." + field + " AS " + spelling + ") FROM Fraction f", session);
            query.prepareCall(session, null);
            assertEquals("SELECT CAST(" + column + " AS " + expectedType + ") FROM FRACTION", query.getSQLString());
            // The SQL type may differ from the requested Java result type, e.g. Oracle NUMBER.
            assertEquals(javaType, query.getItems().get(0).getResultType());
        }
    }

    private void assertCastType(DatabasePlatform platform, String field, String target, String expectedType) {
        DatabaseSessionImpl session = newSession(platform);
        DatabaseQuery query = new HermesParser().buildQuery(
                "SELECT f FROM Fraction f WHERE CAST(f." + field + " AS " + target + ") > :lo", session);
        query.prepareCall(session, null);

        String column = "numerator".equals(field) ? "NUMERATOR" : "TEXT_VALUE";
        assertEquals("SELECT ID, NUMERATOR, TEXT_VALUE FROM FRACTION WHERE (CAST("
                + column + " AS " + expectedType + ") > ?)", query.getSQLString());
    }

    private DatabaseSessionImpl newSession(DatabasePlatform platform) {
        RelationalDescriptor descriptor = new RelationalDescriptor();
        descriptor.setJavaClass(Fraction.class);
        descriptor.setAlias("Fraction");
        descriptor.setTableName("FRACTION");
        descriptor.addPrimaryKeyFieldName("ID");
        descriptor.addDirectMapping("id", "ID");
        descriptor.addDirectMapping("numerator", "NUMERATOR");
        descriptor.addDirectMapping("text", "TEXT_VALUE");

        DatabaseSessionImpl session = new DatabaseSessionImpl(new DatabaseLogin(platform));
        session.addDescriptor(descriptor);
        session.initializeDescriptors();

        return session;
    }

    public static class Fraction {
        public int id;
        public int numerator;
        public String text;
    }
}
