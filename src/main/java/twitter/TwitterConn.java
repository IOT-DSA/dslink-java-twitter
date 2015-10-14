package twitter;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.TwitterStream;
import twitter4j.auth.AccessToken;
import twitter4j.auth.RequestToken;
import twitter4j.conf.ConfigurationBuilder;

import java.util.HashSet;

public class TwitterConn {
	private static final Logger LOGGER;
	static {
		LOGGER = LoggerFactory.getLogger(TwitterConn.class);
	}

	TwitterLink link;
	Node node;
	private Twitter twitter;
	AccessToken accessToken;
	
	HashSet<TwitterStream> openStreams = new HashSet<TwitterStream>();
	
	public TwitterConn(TwitterLink twit, Node node) {
		this.link = twit;
		this.node = node;
		
		Action act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove").setAction(act).build().setSerializable(false);
	}
	
	void init() {
		
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("consumer key", ValueType.STRING, node.getAttribute("consumer key")));
		act.addParameter(new Parameter("consumer secret", ValueType.STRING, node.getAttribute("consumer secret")));
		Node anode = node.getChild("edit");
		if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
		
		Value t = node.getAttribute("token");
		Value ts = node.getAttribute("token secret");
		if (t!=null && ts!=null) accessToken = new AccessToken(t.getString(), ts.getString());
		else accessToken = null;
		
		if (accessToken != null) {
			ConfigurationBuilder cb = new ConfigurationBuilder();
			cb.setDebugEnabled(true)
  			.setOAuthConsumerKey(node.getAttribute("consumer key").getString())
  			.setOAuthConsumerSecret(node.getAttribute("consumer secret").getString())
  			.setOAuthAccessToken(accessToken.getToken())
  			.setOAuthAccessTokenSecret(accessToken.getTokenSecret())
  			.setJSONStoreEnabled(true);		
			TwitterFactory tf = new TwitterFactory(cb.build());
			twitter = tf.getInstance();
			connect();
			return;
		}
		
		ConfigurationBuilder cb = new ConfigurationBuilder();
		cb.setDebugEnabled(true)
			.setOAuthConsumerKey(node.getAttribute("consumer key").getString())
			.setOAuthConsumerSecret(node.getAttribute("consumer secret").getString())
			.setJSONStoreEnabled(true);		
		TwitterFactory tf = new TwitterFactory(cb.build());
		twitter = tf.getInstance();
		try {
			RequestToken requestToken = twitter.getOAuthRequestToken();
			node.createChild("Authorization URL").setValueType(ValueType.STRING).setValue(new Value(requestToken.getAuthorizationURL())).build();
			
			act = new Action(Permission.READ, new AuthHandler(requestToken));
			act.addParameter(new Parameter("pin", ValueType.STRING));
			node.createChild("authorize").setAction(act).build().setSerializable(false);
			
		} catch (TwitterException e) {
			LOGGER.debug("error: ", e);
		}
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String consumerKey = event.getParameter("consumer key", ValueType.STRING).getString();
			String consumerSecret = event.getParameter("consumer secret", ValueType.STRING).getString();
			
			node.setAttribute("consumer key", new Value(consumerKey));
			node.setAttribute("consumer secret", new Value(consumerSecret));
			
			if (name != null && !node.getName().equals(name)) {
				rename(name);
			} else {
				init();
			}
			
		}
	}
	
	private void rename(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject nodeobj = jobj.get(node.getName());
		jobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		TwitterConn tc = new TwitterConn(link, newnode);
		remove();
		tc.restoreLastSession();
	}
	
	private void stop() {
		accessToken = null;
		node.removeAttribute("token");
		node.removeAttribute("token secret");
		for (TwitterStream ts: openStreams) {
			ts.cleanUp();
			ts.shutdown();
			openStreams.remove(ts);
		}
		node.removeChild("start sample stream");
		node.removeChild("start filtered stream");
		node.removeChild("update status");
		node.removeChild("disconnect");
	}
	
	private void remove() {
		stop();
		node.clearChildren();
		node.getParent().removeChild(node);
	}
	
	private class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}
	
	private void connect() {
		
		NodeBuilder builder = node.createChild("start sample stream");
		Action act = new Action(Permission.READ, new SampleHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		builder.setAction(act);
		builder.build().setSerializable(false);
		
		builder = node.createChild("start filtered stream");
		act = new Action(Permission.READ, new FilterHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("locations", ValueType.STRING));
		act.addParameter(new Parameter("track", ValueType.STRING));
		act.addParameter(new Parameter("follow", ValueType.STRING));
		//act.addParameter(new Parameter("count", ValueType.NUMBER));
		act.addParameter(new Parameter("language", ValueType.STRING));
		builder.setAction(act);
		builder.build().setSerializable(false);
		
		builder = node.createChild("update status");
		act = new Action(Permission.READ, new PostHandler());
		act.addParameter(new Parameter("status", ValueType.STRING));
		builder.setAction(act);
		builder.build().setSerializable(false);
		
		act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				stop();
				init();
			}
		});
		node.createChild("disconnect").setAction(act).build().setSerializable(false);
		
	}
	
	private class AuthHandler implements Handler<ActionResult> {
		
		private RequestToken reqToken;
		
		public AuthHandler(RequestToken requestToken) {
			this.reqToken = requestToken;
		}
		public void handle(ActionResult event) {
			String authpin = event.getParameter("pin", ValueType.STRING).getString();
			try{
				if(authpin.length() > 0){
					accessToken = twitter.getOAuthAccessToken(reqToken, authpin);
		        }else{
		        	accessToken = twitter.getOAuthAccessToken();
		        }
				
				node.setAttribute("token", new Value(accessToken.getToken()));
				node.setAttribute("token secret", new Value(accessToken.getTokenSecret()));
				
//				clearErrorMsgs();
				twitter.setOAuthAccessToken(accessToken);
				node.removeChild("authorize");
				node.removeChild("Authorization URL");
				connect();
		    } catch (TwitterException te) {
		    	if(401 == te.getStatusCode()){
//		    		NodeBuilder builder = err.createChild("authorization error message");
//					builder.setValue(new Value("401: Unable to get the access token."));
//					builder.build();
		    		LOGGER.error("Unable to get the access token.");
		        }else{
//		        	NodeBuilder builder = err.createChild("authorization error message");
//					builder.setValue(new Value("Unable to get the access token."));
//					builder.build();
					LOGGER.debug("error: ", te);
		        }
		    }
			
		}
		
	}
	
	private class FilterHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name").getString();
			Value alllocstrings = event.getParameter("locations");
			Value alltrack = event.getParameter("track");
			Value allfollowstrings = event.getParameter("follow");
			//Value count = event.getParameter("count");
			Value alllang = event.getParameter("language");
			
			Node stream = node.createChild(name).setValueType(ValueType.STRING).build();
			stream.setAttribute("filtered", new Value(true));
			if (alllocstrings!=null && !alllocstrings.toString().isEmpty())
				stream.setAttribute("locations", alllocstrings);
			if (alltrack!=null && !alltrack.toString().isEmpty())
                stream.setAttribute("track", alltrack);
			if (allfollowstrings!=null && !allfollowstrings.toString().isEmpty())
                stream.setAttribute("follow", allfollowstrings);
			//if (count!=null) stream.setAttribute("count", count);
			if (alllang!=null && !alllang.toString().isEmpty())
                stream.setAttribute("language", alllang);
			
			StreamNode sn = new StreamNode(getMe(), stream);
			sn.init();
			
			
		}
	}
	
	private class SampleHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name").getString();
			Node stream = node.createChild(name).setValueType(ValueType.MAP).build();
			stream.setAttribute("filtered", new Value(false));
			
			StreamNode sn = new StreamNode(getMe(), stream);
			sn.init();
		}
	}
	
	private class PostHandler implements Handler<ActionResult> {
		public void handle (ActionResult event) {
			String status = event.getParameter("status", ValueType.STRING).getString();
			try {
				twitter.updateStatus(status);
			} catch (TwitterException e) {
//				NodeBuilder builder = err.createChild("post error message");
//				builder.setValue(new Value("Error updating status"));
//				builder.build();
				LOGGER.debug("error: ", e);
			}
		}
	}

	public void restoreLastSession() {
		init();
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value filt = child.getAttribute("filtered");
			if (filt!=null) {
				StreamNode sn = new StreamNode(getMe(), child);
				sn.init();
			} else if (child.getAction() == null) {
				node.removeChild(child);
			}
		}
	}
	
	public TwitterConn getMe() {
		return this;
	}

}
