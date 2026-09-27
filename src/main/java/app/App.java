package app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import com.sun.net.httpserver.HttpServer;

public class App 
{

    static final Path NOTES_DIR = Paths.get("notes");
    static final Parser MARKDOWN_PARSER = Parser.builder().build();
    

    static final HtmlRenderer HTML_RENDERER  = HtmlRenderer.builder().build();
    static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();

    public static void main(String[] args) throws IOException
    {
        int port = 8080;

        Files.createDirectories(NOTES_DIR);

        HttpServer server = HttpServer.create(new InetSocketAddress(port) , 0);

        server.createContext("/notes", exchange -> 
            {
                String method = exchange.getRequestMethod();
                if(method.equalsIgnoreCase("POST"))
                {
                    InputStream is = exchange.getRequestBody();
                    String markdownContent = new String(is.readAllBytes());

                    String noteId = "note-" + System.currentTimeMillis();

                    Path noteFile = NOTES_DIR.resolve(noteId + ".md");

                    Files.writeString(noteFile, markdownContent);

                    String msg = "Saved as " + noteId;
                    exchange.sendResponseHeaders(201, msg.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(msg.getBytes());
                    os.close();
                }

                else if(method.equalsIgnoreCase("GET"))
                {
                    List<String> noteIds = new ArrayList<>();

                    try(DirectoryStream<Path>stream = 
                        Files.newDirectoryStream(NOTES_DIR, "*.md")
                    )
                    {
                        for(Path entry : stream)
                        {
                            String fileName = entry.getFileName().toString();

                            String noteId = fileName.substring(0,fileName.length() - 3);
                            noteIds.add(noteId);
                        }
                    }

                    String msg = noteIds.isEmpty() ? "No notes saved yet." : String.join("\n", noteIds);

                    exchange.sendResponseHeaders(200, msg.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(msg.getBytes());
                    os.close();
                }
                else 
                {
                    String msg = "Only GET and POST are supported on /notes";
                    exchange.sendResponseHeaders(405, msg.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(msg.getBytes());
                    os.close();
                }
    });

                server.createContext("/render", exchange ->
                {
                    if(!exchange.getRequestMethod().equalsIgnoreCase("GET"))
                    {
                        String msg = "Only GET is supported on /render";
                        exchange.sendResponseHeaders(405, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                        return;
                    }

                    String path = exchange.getRequestURI().getPath();
                    String noteId  = path.substring("/render/".length());

                    if(noteId.isEmpty())
                    {
                        String msg = "Usage: GET / render / <noteId>";
                        exchange.sendResponseHeaders(400, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                        return;
                    }

                    Path noteFile = NOTES_DIR.resolve(noteId + ".md");

                    if(!Files.exists(noteFile))
                    {
                        String msg = "No such note: " + noteId;
                        exchange.sendResponseHeaders(404, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                        return;
                    }
                    
                    String markdownContent = Files.readString(noteFile);
                    Node docu = MARKDOWN_PARSER.parse(markdownContent);

                    String html = HTML_RENDERER.render(docu);

                    exchange.getResponseHeaders().set("Content-Type" , "text/html");
                    exchange.sendResponseHeaders(200, html.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(html.getBytes());
                    os.close(); 
                }
                );


                server.createContext("/check-grammar" , exchange -> 
                {
                    if(!exchange.getRequestMethod().equalsIgnoreCase("GET"))
                    {
                        String msg = "Only GET is supported on /check-grammar";
                        exchange.sendResponseHeaders(405, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                        return;
                    }

                    String path = exchange.getRequestURI().getPath();
                    String noteId = path.substring("/check-grammar/".length());

                    if(noteId.isEmpty())
                    {
                        String msg = "Usage: GET /check-grammar/<noteId>";
                        exchange.sendResponseHeaders(400, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                        return;
                    }

                    Path noteFile = NOTES_DIR.resolve(noteId + ".md");

                    if(!Files.exists(noteFile))
                    {
                        String msg = "No such note: " + noteId;
                        exchange.sendResponseHeaders(404, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                        return;
                    }

                    String noteContent = Files.readString(noteFile);
                    
                    try
                    {
                        String encodedText = URLEncoder.encode(noteContent , StandardCharsets.UTF_8);
                        String reqBody = "text=" + encodedText + "&language=en-US";

                        HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.languagetool.org/v2/check"))
                            .header("Content-Type" , "application/x-www-form-urlencoded")
                            .timeout(java.time.Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                            .build();
                    HttpResponse<String>grammRes = HTTP_CLIENT.send(request , HttpResponse.BodyHandlers.ofString());

                    exchange.getResponseHeaders().set("Content-Type" , "application/json");
                    exchange.sendResponseHeaders(200, grammRes.body().getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(grammRes.body().getBytes());
                    os.close();
                    }
                    catch(Exception e)
                    {
                        String msg = "Could not grammer check service";
                        exchange.sendResponseHeaders(502, msg.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(msg.getBytes());
                        os.close();
                    }
                }
                );

        server.setExecutor(null);
        server.start();

        System.out.println("Server started on port " + port);
    }
}
