/*--------------------------------------------------------

1. Original authors: John Reagan and Clark Elliott / 05/20/2012

2. Comments added by: Mingfei Shao / 10/30/2016

3. Notes:
This program is consisted by the following major components:
    1. The HostServer class (the main server): This main server is responsible for accepting the initial connection (request for new host) and generating a next available port number.
    2. The AgentListener class (the child server): This child server is running on the available port specified by the main server and it can either start in initial state or accept an existing state.
       After the setup, this child server will sends its address and port info back to the browser client so that the browser will talk to the child server from now on.
       Moreover, it is in charge of spawning new AgentWorker class to handle the requests send from the browser and reply accordingly.
    3. The AgentWorker class: This is the worker class which is responsible for updating the state counter value for the client or process the migration request received from the browser.
       The implementation of this AgentWorker class is able to maintain different state counters for different client connections and persist the counter values even if a migration has occurred.

4. Workflow:
The workflow of this HostServer is as follow:
    1. The main server HostServer starts running at some location (localhost in this case) and listen to port 1565.
    2. Clients (by using browsers) connect to the main server via port 1565.
    3. The browser will send HTTP GET message to the main server. After receiving this message, the main server generates the next available port number indicating which port should the child server use, and spawn a child server object.
    4. The child server get the next available port number and its address, and build the response HTML web page with the child server address and available port info together with other components (input box, submit button, etc...) and send back to browser.
    5. Furthermore, the child server will also generate agentHolder object to store its current status into that object, including the reference to the server socket and the value of the state counter.
    6. The returned child server address and port info is put in a CGI in format of "<form method="GET" action="Addr:Port">". By specifying the GET method and action "Addr:Port", the browser will know where is the child server and will send messages to it from now on.
    7. The child server waits and handles any requests received from the browser by spawning AgentWorker objects.
    8. The AgentWorker will handle the requests received from the browser. If the request is not a migration, the AgentWorker will just update the state counter value stored in the agentHolder object.
    9. If it is a migration request, then the AgentWorker will choose the address of the new host (which in this case, is still localhost, but could be anything in practice), and send to that new host a request for hosting, including the current state counter value it holds, via port 1565 (so on that address, there must be a HostServer main server that is monitoring port 1565).
    10. After sending the hosting request to the new main server, the AgentWorker waits for reply with the available port number from that server, connects to it via the new port.
    11. Now the migration is completed, the AgentWorker get the reference to the old server socket from the agentHolder object and closes it.

5. Discussions:
I found some small bugs when examining the code. The first thing is in method "sendHTMLsubmit", it appears that the closing bracket for tag <input> is missing, I wrote something down in that section.
The second thing is it looks like the HostServer and AgentListener didn't take care the scenario that if the port number chosen by HostServer is not available to AgentListener, because the way to generate these port numbers is simply increasing the old value by 1.
It would be better if the port number is occupied, then the AgentListener sends back another message to the HostServer and requires a new port.
The third thing is that this program takes care of the GET request for favicon in some brute way: for GET request for favicon, the HostServer will also increase the available port number and spawns AgentListener objects tries to handle them.
So the result is after the first start up of the HostServer, the next request from browser will be send to port 3004 rather than 3001. I think it would be more elegant if the code can ignore the GET request for favicons.
Overall it is a very insightful and well designed program. And I've learnt a lot from it.

----------------------------------------------------------*/

// Get the Input Output libraries
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
// Get the Java networking libraries
import java.net.ServerSocket;
import java.net.Socket;

// AgentWorker class to handle Agent client's migration requests or state update requests, it will get a new port from the new host server and migrate current states to that connection,
// Each worker class will run on a new thread. This can be treat as the child client that will connects to the child server
class AgentWorker extends Thread {
	Socket sock;
	// Customized agentHolder class to store the current Agent client status and connection information
	agentHolder parentAgentHolder; 
	// Port variable to hold the ports that is currently in using
	int localPort; 
	
	// Default constructor
	AgentWorker (Socket s, int prt, agentHolder ah) {
		sock = s;
		localPort = prt;
		parentAgentHolder = ah;
	}

	public void run() {
		PrintStream out = null;
		BufferedReader in = null;
		
		// Specify default address of new main server, which is the same host in this case
		String NewHost = "localhost";
		// Specify default port number of new main server
		int NewHostMainPort = 1565;	
		// Temp string to hold input message from new main server
		String buf = "";
		// Store the available port number returned by the new main server
		int newPort;
		Socket clientSock;
		BufferedReader fromHostServer;
		PrintStream toHostServer;
		
		try {
			out = new PrintStream(sock.getOutputStream());
			in = new BufferedReader(new InputStreamReader(sock.getInputStream()));		
			
			// Read messages received from the browser
			String inLine = in.readLine();
			// Prepare for reply message to the browser
			StringBuilder htmlString = new StringBuilder();
			
			// Output messages received from the browser to console
			System.out.println();
			System.out.println("Request line: " + inLine);
			
			// If messages received contains "migrate" command
			if(inLine.indexOf("migrate") > -1) {
				// Initialize new socket connection from here (client) to the new host serevr (being migrated to)
				clientSock = new Socket(NewHost, NewHostMainPort);
				// Initialize communication channels of the new socket connection
				fromHostServer = new BufferedReader(new InputStreamReader(clientSock.getInputStream()));
				toHostServer = new PrintStream(clientSock.getOutputStream());
				// Send handshake message to the new host serevr requests for hosting, along with current status of this Agent so the status will not lost during transfer
				toHostServer.println("Please host me. Send my port! [State=" + parentAgentHolder.agentState + "]");
				toHostServer.flush();
				
				// Endless for loop for blocking and waiting for the new host server to response with available port
				for(;;) {
					buf = fromHostServer.readLine();
					// If response from new server contains port information
					if(buf.indexOf("[Port=") > -1) {
						// Break from the blocking
						break;
					}
				}
				
				// Get the port number as string from new server's reply message, which has a format as [Port=XX]
				// .indexOf("[Port=") will returns the index of the first "[", so the actual port number starts at 6 positions behind
				// And buf.indexOf("]", buf.indexOf("[Port=") is to find the index of the first "]" after string "[Port="
				String tempbuf = buf.substring( buf.indexOf("[Port=")+6, buf.indexOf("]", buf.indexOf("[Port=")) );
				// Convert the port number from string into integer
				newPort = Integer.parseInt(tempbuf);
				// Out available port information to console
				System.out.println("newPort is: " + newPort);
				
				// Assemble the response message to the browser in HTML format
				// First call helper method sendHTMLheader() to build the original HTML web page using updated server information
				htmlString.append(AgentListener.sendHTMLheader(newPort, NewHost, inLine));
				// Then append new HTML message to show migration information
				htmlString.append("<h3>We are migrating to host " + newPort + "</h3> \n");
				// And append a little bit hints
				htmlString.append("<h3>View the source of this page to see how the client is informed of the new location.</h3> \n");
				// Finally append the HTNL submit suffix
				htmlString.append(AgentListener.sendHTMLsubmit());

				// Finished migration work, begin to kill the old server socket
				System.out.println("Killing parent listening loop.");
				// Get the old server socket which stored in the agentHolder object
				ServerSocket ss = parentAgentHolder.sock;
				// Kill that socket
				ss.close();
			} else if(inLine.indexOf("person") > -1) {
				// Messages received contains "person" command, then update the state counter of this agent
				parentAgentHolder.agentState++;
				
				// Assemble the response message to the browser in HTML format with the updated agent state counter
				htmlString.append(AgentListener.sendHTMLheader(localPort, NewHost, inLine));
				htmlString.append("<h3>We are having a conversation with state   " + parentAgentHolder.agentState + "</h3>\n");
				htmlString.append(AgentListener.sendHTMLsubmit());

			} else {
				// Got a bad request, send notice message back to browser
				htmlString.append(AgentListener.sendHTMLheader(localPort, NewHost, inLine));
				htmlString.append("You have not entered a valid request!\n");
				htmlString.append(AgentListener.sendHTMLsubmit());			
			}
			
			// Use helper method to send the assembled HTML response message back to browser
			AgentListener.sendHTMLtoStream(htmlString.toString(), out);
			sock.close();
		} catch (IOException ioe) {
			// Exception handling
			System.out.println(ioe);
		}
	}
}

// Customized agentHolder class to store the current client's status
class agentHolder {
	// Current socket that the client is using to communicate to the old server
	ServerSocket sock;
	// Current state counter value for this client
	int agentState;
	
	// Defaul constructor
	agentHolder(ServerSocket s) { sock = s;}
}

// AgentListener class to handle Admin client requests on some available ports, each worker class will run on a new thread
// For each time a connection is made to the main server (via port 1565), an AgentListener object will be created so it will have a new server socket with the next available port number to handle the client
class AgentListener extends Thread {
	Socket sock;
	// Port variable to hold the ports that is currently in using
	int localPort;
	
	// Default constructor
	AgentListener(Socket As, int prt) {
		sock = As;
		localPort = prt;
	}
	// Initialize current state counter for this client
	int agentState = 0;
	
	public void run() {
		BufferedReader in = null;
		PrintStream out = null;
		// Specify default address of new host server, which is the same host in this case
		String NewHost = "localhost";
		// Output system message to console
		System.out.println("In AgentListener Thread");		
		try {
			String buf;
			out = new PrintStream(sock.getOutputStream());
			in =  new BufferedReader(new InputStreamReader(sock.getInputStream()));
			
			// Read the first line of message that received from the browser
			buf = in.readLine();
			
			// If the read request contains "state" information
			if(buf != null && buf.indexOf("[State=") > -1) {
				// Get the state counter value as string from browser's message, which has a format as [State=XX]
				// .indexOf("[State=") will returns the index of the first "[", so the actual port number starts at 7 positions behind
				// And buf.indexOf("]", buf.indexOf("[State=") is to find the index of the first "]" after string "[State="
				String tempbuf = buf.substring(buf.indexOf("[State=")+7, buf.indexOf("]", buf.indexOf("[State=")));
				agentState = Integer.parseInt(tempbuf);
				// Output the state counter value to console
				System.out.println("agentState is: " + agentState);
			}
			
			// Output the first line of message that received from the browser to console
			System.out.println(buf);
			
			StringBuilder htmlResponse = new StringBuilder();
			// Assemble the response message to the browser in HTML format
			// First call helper method sendHTMLheader() to build the original HTML web page using updated server information
			htmlResponse.append(sendHTMLheader(localPort, NewHost, buf));
			// Then append a little bit of hints
			htmlResponse.append("Now in Agent Looper starting Agent Listening Loop\n<br />\n");
			// And append new HTML message that includes the information about the next available port assigned by the main server
			htmlResponse.append("[Port="+localPort+"]<br/>\n");
			// Finally append the HTNL submit suffix
			htmlResponse.append(sendHTMLsubmit());
			// Use helper method to send the assembled HTML response message back to browser
			sendHTMLtoStream(htmlResponse.toString(), out);

			// Create a child server socket with the next available port specified by the main server
			ServerSocket servsock = new ServerSocket(localPort,2);
			// Initialize a new agentHolder object to save the new server socket information, for possible closing it in the future
			agentHolder agenthold = new agentHolder(servsock);
            // Save the current client state counter value to the agentHolder object, for possible retrieval in the future
			agenthold.agentState = agentState;

            // Start the child server socket, listen through the next available port specified by the main server and wait for connections
			while(true) {
                // Get connected
				sock = servsock.accept();
                // Output current child server port info to the console
				System.out.println("Got a connection to agent at port " + localPort);
                // Initialize and start a AgentWorker object on another thread to handle the request
				new AgentWorker(sock, localPort, agenthold).start();
			}
		} catch(IOException ioe) {
            // Error handling
			System.out.println("Either connection failed, or just killed listener loop for agent at port " + localPort);
			System.out.println(ioe);
		}
	}

	// Helper method to build the header part of the HTML web page that is being returned to the browser
	static String sendHTMLheader(int localPort, String NewHost, String inLine) {
		StringBuilder htmlString = new StringBuilder();

        // Append basic HTML header
		htmlString.append("<html><head> </head><body>\n");
        // Append level 2 title, which shows the information about the new child server (host address + port number)
		htmlString.append("<h2>This is for submission to PORT " + localPort + " on " + NewHost + "</h2>\n");
        // Append level 2 title, which shows the input information from user in the browser
		htmlString.append("<h3>You sent: "+ inLine + "</h3>");
        // Append the information about the new child server (host address + port number) in the format of CGI
		htmlString.append("\n<form method=\"GET\" action=\"http://" + NewHost +":" + localPort + "\">\n");
        // Append some hints
		htmlString.append("Enter text or <i>migrate</i>:");
        // Append HTML code to setup the input box
		htmlString.append("\n<input type=\"text\" name=\"person\" size=\"20\" value=\"YourTextInput\" /> <p>\n");
		
		return htmlString.toString();
	}

    // Helper method to build the "Submit" button at the bottom part of the HTML web page that is being returned to the browser
    // Seems this method has a small bug, which is missing a closing "/>" for the tag "<input". Modern browsers will take care of that but it could causes trouble in some old browsers
	static String sendHTMLsubmit() {
		return "<input type=\"submit\" value=\"Submit\"" + "</p>\n</form></body></html>\n";
	}

	// Helper method to append the HTTP header to the built HTML web page code and send them back to the browser
	static void sendHTMLtoStream(String html, PrintStream out) {

        out.println("HTTP/1.1 200 OK");
        out.println("Content-Length: " + html.length());
        out.println("Content-Type: text/html");
        out.println("");
        out.println(html);
    }
}


public class HostServer {
    //Define the initial value of the next available port generated by main server, starts from 3000
	public static int NextPort = 3000;
	
	public static void main(String[] a) throws IOException {
		int q_len = 6;
        // Define the default port of the main server
		int port = 1565;
		Socket sock;

        // Initialize the server socket of the main server on localhost:1565
		ServerSocket servsock = new ServerSocket(port, q_len);
        // Output some hints to console
		System.out.println("John Reagan's DIA Master receiver started at port 1565.");
		System.out.println("Connect from 1 to 3 browsers using \"http:\\\\localhost:1565\"\n");
		while(true) {
            // Get the next available port number of the child server
			NextPort = NextPort + 1;
            // Listen through the next available port specified by the main server and wait for connections
			sock = servsock.accept();
            // Output system status to console
			System.out.println("Starting AgentListener at port " + NextPort);
            // If connected, spawn a AgentListener object as the child server to handle
			new AgentListener(sock, NextPort).start();
		}		
	}
}
