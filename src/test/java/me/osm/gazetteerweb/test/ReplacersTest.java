package me.osm.gazetteerweb.test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import me.osm.gazetteer.web.imp.LocationsDumpImporter;
import me.osm.gazetteer.web.utils.OSMDocSinglton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class ReplacersTest {

	private LocationsDumpImporter importer;

	@Before
	public void setUp() throws Exception {
		OSMDocSinglton.initialize("jar");
		importer = new LocationsDumpImporter(null, false);
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testEmpty() {
		LocationsDumpImporter importer = new LocationsDumpImporter(null, false);
		
		try {
			String[] asArray = importer.fuzzyHousenumberIndex("").toArray(new String[]{});
			assertArrayEquals(new String[]{}, asArray);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testNumber() {
		String[] asArray = importer.fuzzyHousenumberIndex("123").toArray(new String[]{});
		assertArrayEquals(new String[]{"123"}, asArray);
	}

	@Test
	public void testNumberAndLetter() {
		Set<String> set = new HashSet<String>(importer.fuzzyHousenumberIndex("15A"));
		
		assertTrue(set.contains("15A"));
		assertTrue(set.contains("15a"));
	}

	@Test
	public void testNumberWithSlash() {
		Set<String> set = new HashSet<String>(importer.fuzzyHousenumberIndex("15/123"));
		assertTrue(set.contains("15/123"));
		assertTrue(set.contains("15"));
		
		set = new HashSet<String>(importer.fuzzyHousenumberIndex("15A/123"));
		assertTrue(set.contains("15A/123"));
		assertTrue(set.contains("15a"));
	}

	@Test
	public void testNumberAndLetterWithSuffix() {
		Set<String> set = new HashSet<String>(importer.fuzzyHousenumberIndex("15Aк1"));
		
		assertTrue(set.contains("15Aк1"));
		assertTrue(set.contains("15a"));
		assertTrue(set.contains("15a к1"));
		
		set = new HashSet<String>(importer.fuzzyHousenumberIndex("15 строение1a"));
		
		assertTrue(set.contains("15 строение 1a"));
		assertTrue(set.contains("15 строение1a"));
		assertTrue(set.contains("15 с1a"));
		assertTrue(set.contains("15с1a"));
		assertTrue(set.contains("15 1a"));
		
		assertFalse(set.contains("15с"));

		set = new HashSet<String>(importer.fuzzyHousenumberIndex("15 стр.1б"));
		
		assertTrue(set.contains("15 стр.1б"));
		assertTrue(set.contains("15 с1б"));
		assertTrue(set.contains("15с1б"));
		
		assertFalse(set.contains("15с"));

		set = new HashSet<String>(importer.fuzzyHousenumberIndex("15к1"));
		
		assertTrue(set.contains("15к1"));
		assertTrue(set.contains("15 к1"));
		
		assertFalse(set.contains("15к"));
	}

	@Test
	public void testNumberAndLetterTire() {
		Set<String> set = new HashSet<String>(importer.fuzzyHousenumberIndex("д. 15-a"));
		
		assertTrue(set.contains("д. 15-a"));
		assertTrue(set.contains("15a"));
	}
	
}
