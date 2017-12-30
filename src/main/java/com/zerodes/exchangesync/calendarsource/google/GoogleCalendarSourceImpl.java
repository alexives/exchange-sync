package com.zerodes.exchangesync.calendarsource.google;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.validator.routines.EmailValidator;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Event.ExtendedProperties;
import com.google.api.services.calendar.model.Event.Organizer;
import com.google.api.services.calendar.model.Event.Reminders;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import com.google.api.services.calendar.model.Events;
import com.zerodes.exchangesync.calendarsource.CalendarSource;
import com.zerodes.exchangesync.dto.AppointmentDto;
import com.zerodes.exchangesync.dto.AppointmentDto.RecurrenceType;
import com.zerodes.exchangesync.dto.PersonDto;
import com.zerodes.exchangesync.settings.Settings;

/**
 * A Calendar data source implementation for Google Calendars.
 */
public class GoogleCalendarSourceImpl implements CalendarSource {
	private static final Logger LOG = LoggerFactory.getLogger(GoogleCalendarSourceImpl.class);

	/** Directory to store user credentials. */
	private static final File DATA_STORE_DIR = new File(System.getProperty("user.home"), ".credentials/exchange_sync_calendar");

	private static final String EXT_PROPERTY_EXCHANGE_ID = "exchangeId";

	private static final String APPLICATION_NAME = "Exchange Sync/1.0";

	private static final String READ_WRITE_CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar";

	private FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(DATA_STORE_DIR);

	private NetHttpTransport httpTransport;

	private final JsonFactory jsonFactory = new JacksonFactory();

	private final Calendar client;

	private final String calendarId;

	private final DateTimeZone calendarTimeZone;

	private final boolean obfuscateEmails;

	private final boolean syncOrganizerAndAttendees;

	/**
	 * Constructor for instantiating the Google Calendars data source.
	 * @param settings the application settings
	 * @throws Exception if an error occurs
	 */
	public GoogleCalendarSourceImpl(final Settings settings) throws Exception {
		if (settings.getUserSettings().needInternetProxy()) {
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(settings.getUserSettings().internetProxyHost(), settings
					.getUserSettings().internetProxyPort()));
			httpTransport = new NetHttpTransport.Builder().setProxy(proxy).build();
		} else {
			httpTransport = new NetHttpTransport.Builder().build();
		}
		obfuscateEmails = settings.getUserSettings().obfuscateAttendeeEmails();
		syncOrganizerAndAttendees = settings.getUserSettings().googleSyncOrganizerAndAttendees();
		LOG.info("Connecting to Google Calendar...");
		final Credential credential = authorize();
		client = new Calendar.Builder(httpTransport, jsonFactory, credential).setApplicationName(APPLICATION_NAME).build();
		calendarId = getCalendarId(settings.getUserSettings().googleCalendarName());
		calendarTimeZone = getCalendarTimeZone(calendarId);
		LOG.info("Connected to Google Calendar.");
	}

	/**
	 * Authorizes the installed application to access user's protected data.
	 */
	private Credential authorize() throws Exception {
		// load client secrets
		GoogleClientSecrets clientSecrets = GoogleClientSecrets
				.load(jsonFactory, new InputStreamReader(GoogleCalendarSourceImpl.class.getResourceAsStream("/client_secrets.json")));
		// set up authorization code flow
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow
				.Builder(httpTransport, jsonFactory, clientSecrets, Collections.singletonList(READ_WRITE_CALENDAR_SCOPE))
				.setDataStoreFactory(dataStoreFactory)
				.build();
		// authorize
		return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
	}

	private String getCalendarId(final String name) throws IOException {
		final CalendarList feed = client.calendarList().list().execute();
		if (feed.getItems() != null) {
			for (final CalendarListEntry entry : feed.getItems()) {
				if (entry.getSummary().equals(name)) {
					return entry.getId();
				}
			}
		}
		return null;
	}

	private DateTimeZone getCalendarTimeZone(final String id) throws IOException {
		final String timeZoneName = client.calendars().get(id).execute().getTimeZone();
		return DateTimeZone.forID(timeZoneName);
	}

	@Override
	public Collection<AppointmentDto> getAllAppointments(final org.joda.time.DateTime startDate, final org.joda.time.DateTime endDate)
			throws IOException {
		final Collection<AppointmentDto> results = new HashSet<AppointmentDto>();
		int page = 1;
		LOG.info("Retrieving Google Calendar events page " + page);
		Events feed = client.events().list(calendarId).execute();
		while (true) {
			if (feed.getItems() != null) {
				for (final Event event : feed.getItems()) {
					if (event.getStatus().equals("cancelled")) {
						continue;
					}
					final org.joda.time.DateTime eventStartDate = convertToJodaDateTime(event.getStart());
					final org.joda.time.DateTime eventEndDate = coalesce(convertToJodaDateTime(event.getEnd()),
							convertToJodaDateTime(event.getStart()));
					if ((eventEndDate.isAfter(startDate) || eventEndDate.isEqual(startDate)) && eventStartDate.isBefore(endDate)) {
						results.add(convertToAppointmentDto(event));
					}
				}
			}
			final String pageToken = feed.getNextPageToken();
			if (pageToken != null && !pageToken.isEmpty()) {
				page++;
				LOG.info("Retrieving Google Calendar events page " + page);
				feed = client.events().list(calendarId).setPageToken(pageToken).execute();
			} else {
				break;
			}
		}
		return results;
	}

	private AppointmentDto convertToAppointmentDto(final Event event) {
		final GoogleAppointmentDto result = new GoogleAppointmentDto();
		result.setGoogleId(event.getId());
		if (event.getExtendedProperties() != null && event.getExtendedProperties().getPrivate() != null) {
			result.setExchangeId(event.getExtendedProperties().getPrivate().get(EXT_PROPERTY_EXCHANGE_ID));
		}
		result.setLastModified(convertToJodaDateTime(event.getUpdated()));
		result.setSummary(event.getSummary());
		result.setDescription(event.getDescription());
		result.setStart(convertToJodaDateTime(event.getStart()));
		result.setEnd(convertToJodaDateTime(event.getEnd()));
		if (event.getEnd().getDateTime() != null) {
			result.setAllDay(event.getEnd().getDateTime().isDateOnly());
		}
		result.setLocation(event.getLocation());
		if (event.getOrganizer() != null) {
			final PersonDto person = new PersonDto();
			person.setName(event.getOrganizer().getDisplayName());
			person.setEmail(event.getOrganizer().getEmail());
			result.setOrganizer(person);
		}
		if (event.getAttendees() != null) {
			final Set<PersonDto> attendees = new HashSet<PersonDto>();
			for (final EventAttendee eventAttendee : event.getAttendees()) {
				final PersonDto person = new PersonDto();
				person.setName(eventAttendee.getDisplayName());
				person.setEmail(eventAttendee.getEmail());
				if (eventAttendee.getOptional() != null) {
					person.setOptional(eventAttendee.getOptional());
				}
				attendees.add(person);
			}
			result.setAttendees(attendees);
		}
		if (event.getReminders() != null && event.getReminders().getOverrides() != null) {
			final EventReminder reminder = event.getReminders().getOverrides().iterator().next();
			result.setReminderMinutesBeforeStart(reminder.getMinutes());
		}
		// TODO: Recurrence

		return result;
	}

	private void populateEventFromAppointmentDto(final AppointmentDto appointmentDto, final Event event) {
		event.setSummary(appointmentDto.getSummary());
		event.setDescription(appointmentDto.getDescription());
		event.setStart(convertToEventDateTime(appointmentDto.getStart(), appointmentDto.isAllDay(), calendarTimeZone));
		event.setEnd(convertToEventDateTime(appointmentDto.getEnd(), appointmentDto.isAllDay(), calendarTimeZone));
		event.setLocation(appointmentDto.getLocation());
		if (syncOrganizerAndAttendees) {
			if (appointmentDto.getOrganizer() != null && appointmentDto.getOrganizer().getEmail() != null) {
				final Organizer organizer = new Organizer();
				organizer.setDisplayName(appointmentDto.getOrganizer().getName());
				organizer.setEmail(obfuscateEmail(appointmentDto.getOrganizer().getEmail()));
				event.setOrganizer(organizer);
			}
			if (appointmentDto.getAttendees() != null) {
				EmailValidator emailValidator = EmailValidator.getInstance();
				final List<EventAttendee> attendees = new ArrayList<EventAttendee>();
				for (final PersonDto attendee : appointmentDto.getAttendees()) {
					if (emailValidator.isValid(attendee.getEmail())) {
						final EventAttendee eventAttendee = new EventAttendee();
						eventAttendee.setDisplayName(attendee.getName());
						eventAttendee.setEmail(obfuscateEmail(attendee.getEmail()));
						eventAttendee.setOptional(attendee.isOptional());
						attendees.add(eventAttendee);
					}
				}
				event.setAttendees(attendees);
			}
		}
		if (appointmentDto.getReminderMinutesBeforeStart() != null) {
			final EventReminder reminder = new EventReminder();
			reminder.setMinutes(appointmentDto.getReminderMinutesBeforeStart());
			reminder.setMethod("popup");
			final Reminders reminders = new Reminders();
			reminders.setUseDefault(false);
			reminders.setOverrides(Collections.singletonList(reminder));
			event.setReminders(reminders);
		}
		if (appointmentDto.getRecurrenceType() != null) {
			String recurrencePattern = "RRULE:";
			if (appointmentDto.getRecurrenceType() == RecurrenceType.DAILY) {
				recurrencePattern = recurrencePattern + "FREQ=DAILY";
			} else if (appointmentDto.getRecurrenceType() == RecurrenceType.WEEKLY) {
				recurrencePattern = recurrencePattern + "FREQ=WEEKLY";
			} else if (appointmentDto.getRecurrenceType() == RecurrenceType.MONTHLY) {
				recurrencePattern = recurrencePattern + "FREQ=MONTHLY";
			} else if (appointmentDto.getRecurrenceType() == RecurrenceType.YEARLY) {
				recurrencePattern = recurrencePattern + "FREQ=YEARLY";
			}
			recurrencePattern = recurrencePattern + ";COUNT=" + appointmentDto.getRecurrenceCount();
			event.setRecurrence(Collections.singletonList(recurrencePattern));
		}
	}

	@Override
	public void addAppointment(final AppointmentDto appointment) throws IOException {
		final Event event = new Event();
		final Map<String, String> privateProperties = new HashMap<String, String>();
		privateProperties.put(EXT_PROPERTY_EXCHANGE_ID, appointment.getExchangeId());
		final ExtendedProperties extProperties = new ExtendedProperties();
		extProperties.setPrivate(privateProperties);
		event.setExtendedProperties(extProperties);
		populateEventFromAppointmentDto(appointment, event);

		client.events().insert(calendarId, event).execute();

		LOG.info("Added Google appointment " + appointment.getSummary());
	}

	@Override
	public void updateAppointment(final AppointmentDto appointment) throws IOException {
		final GoogleAppointmentDto googleAppointmentDto = (GoogleAppointmentDto) appointment;
		final Event event = client.events().get(calendarId, googleAppointmentDto.getGoogleId()).execute();
		populateEventFromAppointmentDto(appointment, event);
		client.events().update(calendarId, event.getId(), event).execute();

		LOG.info("Updated Google appointment " + appointment.getSummary());
	}

	@Override
	public void deleteAppointment(final AppointmentDto appointment) throws IOException {
		final GoogleAppointmentDto googleAppointmentDto = (GoogleAppointmentDto) appointment;
		client.events().delete(calendarId, googleAppointmentDto.getGoogleId()).execute();

		LOG.info("Deleted Google appointment " + appointment.getSummary());
	}

	private static DateTime convertToDateTime(final org.joda.time.DateTime date) {
		return new DateTime(date.getMillis());
	}

	private static DateTime convertToDate(final org.joda.time.DateTime date, final int tzShift) {
		return new DateTime(true, date.getMillis() + tzShift, null);
	}

	private static EventDateTime convertToEventDateTime(final org.joda.time.DateTime date, final boolean isAllDay,
			final DateTimeZone calendarTimeZone) {
		final EventDateTime result = new EventDateTime();
		if (isAllDay) {
			result.setDate(convertToDate(date, calendarTimeZone.getOffset(date.getMillis())));
		} else {
			result.setDateTime(convertToDateTime(date));
		}
		result.setTimeZone(calendarTimeZone.getID());
		return result;
	}

	private static org.joda.time.DateTime convertToJodaDateTime(final DateTime googleTime) {
		if (googleTime == null) {
			return null;
		}
		return new org.joda.time.DateTime(googleTime.getValue(), DateTimeZone.UTC);
	}

	private static org.joda.time.DateTime convertToJodaDateTime(final EventDateTime googleTime) {
		final org.joda.time.DateTime result;
		if (googleTime.getDateTime() == null) {
			result = convertToJodaDateTime(googleTime.getDate());
		} else {
			result = convertToJodaDateTime(googleTime.getDateTime());
		}
		return result;
	}

	private static <T> T coalesce(final T... items) {
		for (final T item : items) {
			if (item != null) {
				return item;
			}
		}
		return null;
	}

	private String obfuscateEmail(final String email) {
		if (obfuscateEmails) {
			return email + ".obfuscate";
		}
		return email;
	}
}
