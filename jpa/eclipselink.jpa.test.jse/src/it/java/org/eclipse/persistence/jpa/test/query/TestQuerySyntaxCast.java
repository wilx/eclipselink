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

package org.eclipse.persistence.jpa.test.query;

import java.util.Locale;
import java.util.function.Consumer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.eclipse.persistence.jpa.JpaEntityManagerFactory;
import org.eclipse.persistence.jpa.test.framework.DDLGen;
import org.eclipse.persistence.jpa.test.framework.Emf;
import org.eclipse.persistence.jpa.test.framework.EmfRunner;
import org.eclipse.persistence.jpa.test.framework.Property;
import org.eclipse.persistence.jpa.test.query.model.CastValue;
import org.eclipse.persistence.platform.database.DatabasePlatform;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

/** Integration coverage for standard JPQL CAST targets on PostgreSQL, MySQL and Oracle. */
@RunWith(EmfRunner.class)
public class TestQuerySyntaxCast {
    @Emf(name = "cast", createTables = DDLGen.DROP_CREATE, classes = CastValue.class,
            properties = @Property(name = "eclipselink.cache.shared.default", value = "false"))
    private EntityManagerFactory emf;

    @Emf(name = "nationalCast", createTables = DDLGen.NONE, classes = CastValue.class,
            properties = {
                    @Property(name = "eclipselink.cache.shared.default", value = "false"),
                    @Property(name = "eclipselink.target-database-properties",
                            value = "UseNationalCharacterVaryingTypeForString=true")
            })
    private EntityManagerFactory nationalEmf;

    @Before
    public void supportedPlatform() {
        DatabasePlatform platform = platform();
        assumeTrue(platform.isPostgreSQL() || platform.isMySQL() || platform.isOracle());
    }

    @Test
    public void standardTargetsRetainJavaResultTypes() {
        withValue(emf, "5", em -> {
            for (Class<?> type : new Class<?>[] {String.class, Integer.class, Long.class, Float.class, Double.class}) {
                String name = type.getSimpleName();
                for (String spelling : new String[] {name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)}) {
                    String field = type == String.class ? "id" : "text";
                    Object value = cast(em, "v." + field, spelling, type);
                    assertEquals(type, value.getClass());
                    if (value instanceof Number number) {
                        assertEquals(5.0, number.doubleValue(), 0.0);
                    } else {
                        assertEquals("1", value);
                    }
                }
            }
        });
    }

    @Test
    public void stringCastPreservesLengthUnicodeAndTrailingSpaces() {
        for (int length : new int[] {255, 256, 300, 1000}) {
            // Stay within the source column's 1000-byte Oracle limit as well.
            String input = length == 1000 ? "x".repeat(998) + "  " : "x".repeat(length - 4) + "é漢  ";
            withValue(emf, input, em -> assertEquals(input, cast(em, "v.text", "String", String.class)));
        }
    }

    @Test
    public void nationalStringCastPreservesUnicodeAndTrailingSpaces() {
        String input = "é漢".repeat(150) + "  ";
        withValue(nationalEmf, input, em -> assertEquals(input, cast(em, "v.text", "String", String.class)));
    }

    @Test
    public void stringCastBeyond4000Characters() {
        assumeTrue(platform().isPostgreSQL() || platform().isMySQL());
        String input = "x".repeat(999) + " ";
        for (EntityManagerFactory factory : new EntityManagerFactory[] {emf, nationalEmf}) {
            withValue(factory, input, em -> assertEquals(input.repeat(5),
                    cast(em, repeatedText(5), "String", String.class)));
        }
    }

    @Test
    public void oracleStringCastAtStandardLimit() {
        assumeTrue(platform().isOracle());
        String input = "x".repeat(999) + " ";
        withValue(emf, input, em -> assertEquals(input.repeat(4),
                cast(em, repeatedText(4), "String", String.class)));
    }

    @Test
    public void oracleNationalStringCastAtStandardLimit() {
        assumeTrue(platform().isOracle());
        // 2000 national characters occupy 4000 bytes with AL16UTF16.
        String input = "é".repeat(499) + " ";
        withValue(nationalEmf, input, em -> assertEquals(input.repeat(4),
                cast(em, repeatedText(4), "String", String.class)));
    }

    @Test
    public void floatingCastsRetainFractionalValues() {
        for (String input : new String[] {"12.75", "1.23456789"}) {
            withValue(emf, input, em -> {
                // MySQL's FLOAT result can have fewer decimal digits on the JDBC
                // text protocol. Match the native cast, without imposing a column scale.
                String nativeType = platform().isOracle() ? "NUMBER" : "FLOAT";
                Number expected = (Number) em.createNativeQuery(
                        "SELECT CAST(TEXT_VALUE AS " + nativeType + ") FROM JPQL_CAST_VALUE").getSingleResult();
                Float actual = cast(em, "v.text", "Float", Float.class);
                assertEquals(Float.valueOf(expected.floatValue()), actual);
                assertEquals(Float.parseFloat(input), actual, 0.00001f);
                assertEquals(Double.valueOf(input), cast(em, "v.text", "Double", Double.class));
            });
        }
    }

    @Test
    public void integerCastsRetainJavaRange() {
        for (Integer input : new Integer[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            withValue(emf, input.toString(), em -> assertEquals(input, cast(em, "v.text", "Integer", Integer.class)));
        }
        for (Long input : new Long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
            withValue(emf, input.toString(), em -> assertEquals(input, cast(em, "v.text", "Long", Long.class)));
        }
    }

    @Test
    public void integerConversionMatchesNativeDatabaseRounding() {
        withValue(emf, "12.75", em -> {
            String nativeType = platform().isOracle() ? "NUMBER(10)" : platform().isMySQL() ? "SIGNED" : "INTEGER";
            String floatingType = platform().isOracle() ? "NUMBER" : platform().isMySQL() ? "DOUBLE" : "FLOAT";
            // Rounding also depends on the source SQL type: e.g. MySQL DECIMAL vs DOUBLE.
            Number expected = (Number) em.createNativeQuery("SELECT CAST(CAST(TEXT_VALUE AS " + floatingType
                    + ") AS " + nativeType + ") FROM JPQL_CAST_VALUE")
                    .getSingleResult();
            assertEquals(Integer.valueOf(expected.intValue()),
                    cast(em, "CAST(v.text AS Double)", "Integer", Integer.class));
        });
    }

    @Test
    public void nullCastsRemainNull() {
        withValue(emf, null, em -> {
            for (Class<?> type : new Class<?>[] {String.class, Integer.class, Long.class, Float.class, Double.class}) {
                assertNull(cast(em, "v.text", type.getSimpleName(), type));
            }
        });
    }

    @Test
    public void doubleCastInPredicate() {
        withValue(emf, "5", em -> {
            for (String field : new String[] {"id", "text"}) {
                assertEquals(Integer.valueOf(1), em.createQuery(
                        "SELECT v.id FROM CastValue v WHERE CAST(v." + field + " AS Double) > :lo", Integer.class)
                        .setParameter("lo", 0.5).getSingleResult());
            }
        });
    }

    @Test
    public void nestedCastRetainsOuterType() {
        withValue(emf, "5", em -> assertEquals(Double.valueOf(5),
                cast(em, "CAST(v.text AS Long)", "Double", Double.class)));
    }

    @Test
    public void explicitDatabaseCastRemainsAvailable() {
        withValue(emf, "12", em -> assertEquals("12", cast(em, "v.text", "CHAR(2)", String.class)));
    }

    private <T> T cast(EntityManager em, String expression, String target, Class<T> type) {
        return em.createQuery("SELECT CAST(" + expression + " AS " + target + ") FROM CastValue v", type).getSingleResult();
    }

    private String repeatedText(int count) {
        String expression = "v.text";
        for (int i = 1; i < count; i++) {
            expression = "CONCAT(" + expression + ", v.text)";
        }
        return expression;
    }

    private DatabasePlatform platform() {
        return emf.unwrap(JpaEntityManagerFactory.class).getServerSession().getPlatform();
    }

    private void withValue(EntityManagerFactory factory, String text, Consumer<EntityManager> assertions) {
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin();
            em.persist(new CastValue(1, text));
            em.flush();
            assertions.accept(em);
        } finally {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }
}
