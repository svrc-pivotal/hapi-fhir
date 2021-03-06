package ca.uhn.fhir.jpa.dao.dstu3;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.InstantType;
import org.hl7.fhir.dstu3.model.Organization;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.UriType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.interceptor.IServerInterceptor.ActionRequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.TestUtil;

public class FhirResourceDaoDstu3UpdateTest extends BaseJpaDstu3Test {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(FhirResourceDaoDstu3UpdateTest.class);

	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}


	@Test
	public void testUpdateAndGetHistoryResource() throws InterruptedException {
		Patient patient = new Patient();
		patient.addIdentifier().setSystem("urn:system").setValue("001");
		patient.addName().addFamily("Tester").addGiven("Joe");

		MethodOutcome outcome = myPatientDao.create(patient, mySrd);
		assertNotNull(outcome.getId());
		assertFalse(outcome.getId().isEmpty());

		assertEquals("1", outcome.getId().getVersionIdPart());

		Date now = new Date();
		Patient retrieved = myPatientDao.read(outcome.getId(), mySrd);
		InstantType updated = retrieved.getMeta().getLastUpdatedElement().copy();
		assertTrue(updated.before(now));

		Thread.sleep(1000);

		reset(myInterceptor);
		retrieved.getIdentifier().get(0).setValue("002");
		MethodOutcome outcome2 = myPatientDao.update(retrieved, mySrd);
		assertEquals(outcome.getId().getIdPart(), outcome2.getId().getIdPart());
		assertNotEquals(outcome.getId().getVersionIdPart(), outcome2.getId().getVersionIdPart());
		assertEquals("2", outcome2.getId().getVersionIdPart());

		// Verify interceptor
		ArgumentCaptor<ActionRequestDetails> detailsCapt = ArgumentCaptor.forClass(ActionRequestDetails.class);
		verify(myInterceptor).incomingRequestPreHandled(eq(RestOperationTypeEnum.UPDATE), detailsCapt.capture());
		ActionRequestDetails details = detailsCapt.getValue();
		assertNotNull(details.getId());
		assertEquals("Patient", details.getResourceType());
		assertEquals(Patient.class, details.getResource().getClass());

		Date now2 = new Date();

		Patient retrieved2 = myPatientDao.read(outcome.getId().toVersionless(), mySrd);

		assertEquals("2", retrieved2.getIdElement().getVersionIdPart());
		assertEquals("002", retrieved2.getIdentifier().get(0).getValue());
		InstantType updated2 = retrieved2.getMeta().getLastUpdatedElement();
		assertTrue(updated2.after(now));
		assertTrue(updated2.before(now2));

		Thread.sleep(2000);

		/*
		 * Get history
		 */

		IBundleProvider historyBundle = myPatientDao.history(outcome.getId(), null, null, mySrd);

		assertEquals(2, historyBundle.size());
		
		List<IBaseResource> history = historyBundle.getResources(0, 2);
		
		ourLog.info("updated : {}", updated.getValueAsString());
		ourLog.info("  * Exp : {}", ((Resource) history.get(1)).getMeta().getLastUpdatedElement().getValueAsString());
		ourLog.info("updated2: {}", updated2.getValueAsString());
		ourLog.info("  * Exp : {}", ((Resource) history.get(0)).getMeta().getLastUpdatedElement().getValueAsString());
		
		assertEquals("1", history.get(1).getIdElement().getVersionIdPart());
		assertEquals("2", history.get(0).getIdElement().getVersionIdPart());
		assertEquals(updated.getValueAsString(), ((Resource) history.get(1)).getMeta().getLastUpdatedElement().getValueAsString());
		assertEquals("001", ((Patient) history.get(1)).getIdentifier().get(0).getValue());
		assertEquals(updated2.getValueAsString(), ((Resource) history.get(0)).getMeta().getLastUpdatedElement().getValueAsString());
		assertEquals("002", ((Patient) history.get(0)).getIdentifier().get(0).getValue());

	}

	@Test
	public void testUpdateByUrl() {
		String methodName = "testUpdateByUrl";

		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		IIdType id = myPatientDao.create(p, mySrd).getId();
		ourLog.info("Created patient, got it: {}", id);

		p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		p.addName().addFamily("Hello");
		p.setId("Patient/" + methodName);

		myPatientDao.update(p, "Patient?identifier=urn%3Asystem%7C" + methodName, mySrd);

		p = myPatientDao.read(id.toVersionless(), mySrd);
		assertThat(p.getIdElement().toVersionless().toString(), not(containsString("test")));
		assertEquals(id.toVersionless(), p.getIdElement().toVersionless());
		assertNotEquals(id, p.getIdElement());
		assertThat(p.getIdElement().toString(), endsWith("/_history/2"));

	}

	@Test
	public void testCreateAndUpdateWithoutRequest() throws Exception {
		String methodName = "testUpdateByUrl";

		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName + "2");
		IIdType id = myPatientDao.create(p).getId().toUnqualified();
		
		p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName + "2");
		IIdType id2 = myPatientDao.create(p, "Patient?identifier=urn:system|" + methodName + "2").getId().toUnqualified();
		assertEquals(id.getValue(), id2.getValue());
		
		p = new Patient();
		p.setId(id);
		p.addIdentifier().setSystem("urn:system").setValue(methodName + "2");
		myPatientDao.update(p).getId();

		id2 = myPatientDao.update(p, "Patient?identifier=urn:system|" + methodName + "2").getId().toUnqualified();
		assertEquals(id.getIdPart(), id2.getIdPart());
		assertEquals("3", id2.getVersionIdPart());

		Patient newPatient = myPatientDao.read(id);
		assertEquals("1", newPatient.getIdElement().getVersionIdPart());

		newPatient = myPatientDao.read(id.toVersionless());
		assertEquals("3", newPatient.getIdElement().getVersionIdPart());
		
		myPatientDao.delete(id.toVersionless());
		
		try {
			myPatientDao.read(id.toVersionless());
			fail();
		} catch (ResourceGoneException e) {
			// nothing
		}
		
	}
	
	
	@Test
	public void testUpdateConditionalByLastUpdated() throws Exception {
		String methodName = "testUpdateByUrl";

		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName + "2");
		myPatientDao.create(p, mySrd).getId();

		InstantDt start = InstantDt.withCurrentTime();
		ourLog.info("First time: {}", start.getValueAsString());
		Thread.sleep(100);
		
		p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		IIdType id = myPatientDao.create(p, mySrd).getId();
		ourLog.info("Created patient, got ID: {}", id);

		Thread.sleep(100);

		p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		p.addName().addFamily("Hello");
		p.setId("Patient/" + methodName);

		String matchUrl = "Patient?_lastUpdated=gt" + start.getValueAsString();
		ourLog.info("URL is: {}", matchUrl);
		myPatientDao.update(p, matchUrl, mySrd);

		p = myPatientDao.read(id.toVersionless(), mySrd);
		assertThat(p.getIdElement().toVersionless().toString(), not(containsString("test")));
		assertEquals(id.toVersionless(), p.getIdElement().toVersionless());
		assertNotEquals(id, p.getIdElement());
		assertThat(p.getIdElement().toString(), endsWith("/_history/2"));

	}

	@Test
	public void testUpdateConditionalByLastUpdatedWithWrongTimezone() throws Exception {
		TimeZone def = TimeZone.getDefault();
		try {
		TimeZone.setDefault(TimeZone.getTimeZone("GMT-0:00"));
		String methodName = "testUpdateByUrl";

		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName + "2");
		myPatientDao.create(p, mySrd).getId();

		InstantDt start = InstantDt.withCurrentTime();
		Thread.sleep(100);
		
		p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		IIdType id = myPatientDao.create(p, mySrd).getId();
		ourLog.info("Created patient, got it: {}", id);

		Thread.sleep(100);

		p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		p.addName().addFamily("Hello");
		p.setId("Patient/" + methodName);

		myPatientDao.update(p, "Patient?_lastUpdated=gt" + start.getValueAsString(), mySrd);

		p = myPatientDao.read(id.toVersionless(), mySrd);
		assertThat(p.getIdElement().toVersionless().toString(), not(containsString("test")));
		assertEquals(id.toVersionless(), p.getIdElement().toVersionless());
		assertNotEquals(id, p.getIdElement());
		assertThat(p.getIdElement().toString(), endsWith("/_history/2"));
		} finally {
			TimeZone.setDefault(def);
		}
	}

	@Test
	public void testUpdateCreatesTextualIdIfItDoesntAlreadyExist() {
		Patient p = new Patient();
		String methodName = "testUpdateCreatesTextualIdIfItDoesntAlreadyExist";
		p.addIdentifier().setSystem("urn:system").setValue(methodName);
		p.addName().addFamily("Hello");
		p.setId("Patient/" + methodName);

		IIdType id = myPatientDao.update(p, mySrd).getId();
		assertEquals("Patient/" + methodName, id.toUnqualifiedVersionless().getValue());

		p = myPatientDao.read(id, mySrd);
		assertEquals(methodName, p.getIdentifier().get(0).getValue());
	}

	@Test
	public void testUpdateDoesntFailForUnknownIdWithNumberThenText() {
		String methodName = "testUpdateFailsForUnknownIdWithNumberThenText";
		Patient p = new Patient();
		p.setId("0" + methodName);
		p.addName().addFamily(methodName);

		myPatientDao.update(p, mySrd);
	}

	/**
	 * Per the spec, update should preserve tags and security labels but not profiles
	 */
	@Test
	public void testUpdateMaintainsTagsAndSecurityLabels() throws InterruptedException {
		String methodName = "testUpdateMaintainsTagsAndSecurityLabels";

		IIdType p1id;
		{
			Patient p1 = new Patient();
			p1.addName().addFamily(methodName);
			
			p1.getMeta().addTag("tag_scheme1", "tag_term1",null);
			p1.getMeta().addSecurity("sec_scheme1", "sec_term1",null);
			p1.getMeta().addProfile("http://foo1");

			p1id = myPatientDao.create(p1, mySrd).getId().toUnqualifiedVersionless();
		}
		{
			Patient p1 = new Patient();
			p1.setId(p1id);
			p1.addName().addFamily(methodName);

			p1.getMeta().addTag("tag_scheme2", "tag_term2", null);
			p1.getMeta().addSecurity("sec_scheme2", "sec_term2", null);
			p1.getMeta().addProfile("http://foo2");

			myPatientDao.update(p1, mySrd);
		}
		{
			Patient p1 = myPatientDao.read(p1id, mySrd);
			List<Coding> tagList = p1.getMeta().getTag();
			Set<String> secListValues = new HashSet<String>();
			for (Coding next : tagList) {
				secListValues.add(next.getSystemElement().getValue() + "|" + next.getCodeElement().getValue());
			}
			assertThat(secListValues, containsInAnyOrder("tag_scheme1|tag_term1", "tag_scheme2|tag_term2"));
			List<Coding> secList = p1.getMeta().getSecurity();
			secListValues = new HashSet<String>();
			for (Coding next : secList) {
				secListValues.add(next.getSystemElement().getValue() + "|" + next.getCodeElement().getValue());
			}
			assertThat(secListValues, containsInAnyOrder("sec_scheme1|sec_term1", "sec_scheme2|sec_term2"));
			List<UriType> profileList = p1.getMeta().getProfile();
			assertEquals(1, profileList.size());
			assertEquals("http://foo2", profileList.get(0).getValueAsString()); // no foo1
		}
	}

	@Test
	public void testUpdateMaintainsSearchParams() throws InterruptedException {
		Patient p1 = new Patient();
		p1.addIdentifier().setSystem("urn:system").setValue("testUpdateMaintainsSearchParamsDstu2AAA");
		p1.addName().addFamily("Tester").addGiven("testUpdateMaintainsSearchParamsDstu2AAA");
		IIdType p1id = myPatientDao.create(p1, mySrd).getId();

		Patient p2 = new Patient();
		p2.addIdentifier().setSystem("urn:system").setValue("testUpdateMaintainsSearchParamsDstu2BBB");
		p2.addName().addFamily("Tester").addGiven("testUpdateMaintainsSearchParamsDstu2BBB");
		myPatientDao.create(p2, mySrd).getId();

		Set<Long> ids = myPatientDao.searchForIds(Patient.SP_GIVEN, new StringParam("testUpdateMaintainsSearchParamsDstu2AAA"));
		assertEquals(1, ids.size());
		assertThat(ids, contains(p1id.getIdPartAsLong()));

		// Update the name
		p1.getName().get(0).getGiven().get(0).setValue("testUpdateMaintainsSearchParamsDstu2BBB");
		MethodOutcome update2 = myPatientDao.update(p1, mySrd);
		IIdType p1id2 = update2.getId();

		ids = myPatientDao.searchForIds(Patient.SP_GIVEN, new StringParam("testUpdateMaintainsSearchParamsDstu2AAA"));
		assertEquals(0, ids.size());

		ids = myPatientDao.searchForIds(Patient.SP_GIVEN, new StringParam("testUpdateMaintainsSearchParamsDstu2BBB"));
		assertEquals(2, ids.size());

		// Make sure vreads work
		p1 = myPatientDao.read(p1id, mySrd);
		assertEquals("testUpdateMaintainsSearchParamsDstu2AAA", p1.getName().get(0).getGivenAsSingleString());

		p1 = myPatientDao.read(p1id2, mySrd);
		assertEquals("testUpdateMaintainsSearchParamsDstu2BBB", p1.getName().get(0).getGivenAsSingleString());

	}

	@Test
	public void testUpdateRejectsInvalidTypes() throws InterruptedException {
		Patient p1 = new Patient();
		p1.addIdentifier().setSystem("urn:system").setValue("testUpdateRejectsInvalidTypes");
		p1.addName().addFamily("Tester").addGiven("testUpdateRejectsInvalidTypes");
		IIdType p1id = myPatientDao.create(p1, mySrd).getId();

		Organization p2 = new Organization();
		p2.getNameElement().setValue("testUpdateRejectsInvalidTypes");
		try {
			p2.setId(new IdType("Organization/" + p1id.getIdPart()));
			myOrganizationDao.update(p2, mySrd);
			fail();
		} catch (UnprocessableEntityException e) {
			// good
		}

		try {
			p2.setId(new IdType("Patient/" + p1id.getIdPart()));
			myOrganizationDao.update(p2, mySrd);
			fail();
		} catch (UnprocessableEntityException e) {
			ourLog.error("Good", e);
		}

	}

	@Test
	@Ignore
	public void testUpdateIgnoresIdenticalVersions() throws InterruptedException {
		String methodName = "testUpdateIgnoresIdenticalVersions";
		
		Patient p1 = new Patient();
		p1.addIdentifier().setSystem("urn:system").setValue(methodName);
		p1.addName().addFamily("Tester").addGiven(methodName);
		IIdType p1id = myPatientDao.create(p1, mySrd).getId();

		IIdType p1id2 = myPatientDao.update(p1, mySrd).getId();
		assertEquals(p1id.getValue(), p1id2.getValue());
		
		p1.addName().addGiven("NewGiven");
		IIdType p1id3 = myPatientDao.update(p1, mySrd).getId();
		assertNotEquals(p1id.getValue(), p1id3.getValue());
		
	}

	@Test
	public void testDuplicateProfilesIgnored() {
		String name = "testDuplicateProfilesIgnored";
		IIdType id;
		{
			Patient patient = new Patient();
			patient.addName().addFamily(name);

			List<IdType> tl = new ArrayList<IdType>();
			tl.add(new IdType("http://foo/bar"));
			tl.add(new IdType("http://foo/bar"));
			tl.add(new IdType("http://foo/bar"));
			patient.getMeta().getProfile().addAll(tl);

			id = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();
		}

		// Do a read
		{
			Patient patient = myPatientDao.read(id, mySrd);
			List<UriType> tl = patient.getMeta().getProfile();
			assertEquals(1, tl.size());
			assertEquals("http://foo/bar", tl.get(0).getValue());
		}

	}

	@Test
	public void testUpdateModifiesProfiles() {
		String name = "testUpdateModifiesProfiles";
		IIdType id;
		{
			Patient patient = new Patient();
			patient.addName().addFamily(name);

			List<IdType> tl = new ArrayList<IdType>();
			tl.add(new IdType("http://foo/bar"));
			patient.getMeta().getProfile().addAll(tl);

			id = myPatientDao.create(patient, mySrd).getId().toUnqualifiedVersionless();
		}

		// Do a read
		{
			Patient patient = myPatientDao.read(id, mySrd);
			List<UriType> tl = patient.getMeta().getProfile();
			assertEquals(1, tl.size());
			assertEquals("http://foo/bar", tl.get(0).getValue());
		}

		// Update
		{
			Patient patient = new Patient();
			patient.setId(id);
			patient.addName().addFamily(name);

			List<IdType> tl = new ArrayList<IdType>();
			tl.add(new IdType("http://foo/baz"));
			patient.getMeta().getProfile().clear();
			patient.getMeta().getProfile().addAll(tl);

			id = myPatientDao.update(patient, mySrd).getId().toUnqualifiedVersionless();
		}

		// Do a read
		{
			Patient patient = myPatientDao.read(id, mySrd);
			List<UriType> tl = patient.getMeta().getProfile();
			assertEquals(1, tl.size());
			assertEquals("http://foo/baz", tl.get(0).getValue());
		}

	}

	@Test
	public void testUpdateUnknownNumericIdFails() {
		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue("testCreateNumericIdFails");
		p.addName().addFamily("Hello");
		p.setId("Patient/9999999999999999");
		try {
			myPatientDao.update(p, mySrd);
			fail();
		} catch (InvalidRequestException e) {
			assertThat(e.getMessage(), containsString("Can not create resource with ID[9999999999999999], no resource with this ID exists and clients may only"));
		}
	}

	@Test
	public void testUpdateWithInvalidIdFails() {
		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue("testCreateNumericIdFails");
		p.addName().addFamily("Hello");
		p.setId("Patient/123:456");
		try {
			myPatientDao.update(p, mySrd);
			fail();
		} catch (InvalidRequestException e) {
			assertEquals("Can not process entity with ID[123:456], this is not a valid FHIR ID", e.getMessage());
		}
	}

	@Test
	public void testUpdateWithNumericIdFails() {
		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue("testCreateNumericIdFails");
		p.addName().addFamily("Hello");
		p.setId("Patient/123");
		try {
			myPatientDao.update(p, mySrd);
			fail();
		} catch (InvalidRequestException e) {
			assertThat(e.getMessage(), containsString("clients may only assign IDs which contain at least one non-numeric"));
		}
	}

	@Test
	public void testUpdateWithNumericThenTextIdSucceeds() {
		Patient p = new Patient();
		p.addIdentifier().setSystem("urn:system").setValue("testCreateNumericIdFails");
		p.addName().addFamily("Hello");
		p.setId("Patient/123abc");
		IIdType id = myPatientDao.update(p, mySrd).getId();
		assertEquals("123abc", id.getIdPart());
		assertEquals("1", id.getVersionIdPart());

		p = myPatientDao.read(id.toUnqualifiedVersionless(), mySrd);
		assertEquals("Patient/123abc", p.getIdElement().toUnqualifiedVersionless().getValue());
		assertEquals("Hello", p.getName().get(0).getFamily().get(0).getValue());

	}

}
