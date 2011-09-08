package winterwell.jtwitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import winterwell.jtwitter.Twitter.ITweet;
import winterwell.jtwitter.Twitter.KEntityType;
import winterwell.jtwitter.Twitter.TweetEntity;

/**
	 * A Twitter direct message. Fields are null if unset.
	 *
	 * TODO are there more fields now? check the raw json
	 */
	public final class Message implements ITweet {
		
		@Override
		public List<String> getMentions() {
			return Collections.singletonList(recipient.screenName);
		}
		
		public String getLocation() {
			return location;
		}
		
		private static final long serialVersionUID = 1L;
		/**
		 * Equivalent to {@link Status#inReplyToStatusId} *but null by default*.
		 * If you want to use this, you must set it yourself. The field is just
		 * a convenient storage place. Strangely Twitter don't report the
		 * previous ID for messages.
		 */
		public Number inReplyToMessageId;

		/**
		 * Tests by class=Message and tweet id number
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Message other = (Message) obj;
			return id.equals(other.id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}

		/**
		 *
		 * @param json
		 * @return
		 * @throws TwitterException
		 */
		static List<Message> getMessages(String json) throws TwitterException {
			if (json.trim().equals(""))
				return Collections.emptyList();
			try {
				List<Message> msgs = new ArrayList<Message>();
				JSONArray arr = new JSONArray(json);
				for (int i = 0; i < arr.length(); i++) {
					JSONObject obj = arr.getJSONObject(i);
					Message u = new Message(obj);
					msgs.add(u);
				}
				return msgs;
			} catch (JSONException e) {
				throw new TwitterException.Parsing(json, e);
			}
		}

		private final Date createdAt;
		final Long id;
		private final User recipient;
		private final User sender;
		public final String text;
		private EnumMap<KEntityType, List<TweetEntity>> entities;
		private String location;
		private Place place;

		
		public List<TweetEntity> getTweetEntities(KEntityType type) {
			return entities==null? null : entities.get(type);
		}
		
		/**
		 * @param obj
		 * @throws JSONException
		 * @throws TwitterException
		 */
		Message(JSONObject obj) throws JSONException, TwitterException {
			// No need for BigInteger - yet
//			String _id = obj.getString("id_str");
//			id = new BigInteger(_id==null? ""+obj.get("id") : _id);
			id = obj.getLong("id");
			String _text = obj.getString("text");
			text = InternalUtils.unencode(_text);
			String c = InternalUtils.jsonGet("created_at", obj);
			createdAt = InternalUtils.parseDate(c);
			sender = new User(obj.getJSONObject("sender"), null);
			// recipient - for messages you sent
			Object recip = obj.opt("recipient");
			if (recip instanceof JSONObject) { // Note JSONObject.has is dangerously misleading
				recipient = new User((JSONObject) recip, null);
			} else {
				recipient = null;
			}
			JSONObject jsonEntities = obj.optJSONObject("entities");
			if (jsonEntities!=null) {
				// Note: Twitter filters out dud @names
				entities = new EnumMap<Twitter.KEntityType, List<TweetEntity>>(KEntityType.class);
				for(KEntityType type : KEntityType.values()) {
					List<TweetEntity> es = TweetEntity.parse(this, type, jsonEntities);
					entities.put(type, es);
				}
			}
			// geo-location?
			Object _locn = Status.jsonGetLocn(obj);
			location = _locn==null? null : _locn.toString();
			if (_locn instanceof Place) {
				place = (Place) _locn;
			}
		}
		
		@Override
		public Place getPlace() {
			return place;
		}

		public Date getCreatedAt() {
			return createdAt;
		}

		/**
		 * @return The Twitter id for this post. This is used by some API
		 *         methods.
		 *         <p>
		 *         Note: this may switch to BigInteger in the future, if Twitter
		 *         change their id numbering scheme. Use Number (which is a super-class
		 *         for both Long and BigInteger) if you wish to future-proof your code.
		 */
		public Long getId() {
			return id;
		}

		/**
		 * @return the recipient (for messages sent by the authenticating user)
		 */
		public User getRecipient() {
			return recipient;
		}

		public User getSender() {
			return sender;
		}

		public String getText() {
			return text;
		}

		/**
		 * This is equivalent to {@link #getSender()}
		 */
		public User getUser() {
			return getSender();
		}

		@Override
		public String toString() {
			return text;
		}

	}