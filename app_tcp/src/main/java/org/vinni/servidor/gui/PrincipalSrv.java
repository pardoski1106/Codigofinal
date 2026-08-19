package org.vinni.servidor.gui;


import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Vinni
 */
public class PrincipalSrv extends javax.swing.JFrame {
    private final int PORT = 12345;
    private ServerSocket serverSocket;

    // Lista thread-safe con todos los clientes conectados actualmente.
    private final List<ClienteHandler> clientesConectados = new CopyOnWriteArrayList<>();
    private final AtomicInteger contadorClientes = new AtomicInteger(0);

    // Prefijo que marca una línea como "identificador de cliente" en vez de un
    // mensaje normal de chat. El cliente (PrincipalCli) reconoce este mismo
    // prefijo para mostrar su propio número.
    static final String PREFIJO_ID_CLIENTE = "ID_ASIGNADO:";

    /**
     * Creates new form Principal1
     */
    public PrincipalSrv() {
        initComponents();
    }
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">
    private void initComponents() {
        this.setTitle("Servidor ...");

        bIniciar = new javax.swing.JButton();
        jLabel1 = new javax.swing.JLabel();
        mensajesTxt = new JTextArea();
        jScrollPane1 = new javax.swing.JScrollPane();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        getContentPane().setLayout(null);

        bIniciar.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        bIniciar.setText("INICIAR SERVIDOR");
        bIniciar.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                bIniciarActionPerformed(evt);
            }
        });
        getContentPane().add(bIniciar);
        bIniciar.setBounds(100, 90, 250, 40);

        jLabel1.setFont(new java.awt.Font("Tahoma", 1, 14)); // NOI18N
        jLabel1.setForeground(new java.awt.Color(204, 0, 0));
        jLabel1.setText("SERVIDOR TCP : HOEL");
        getContentPane().add(jLabel1);
        jLabel1.setBounds(150, 10, 160, 17);

        mensajesTxt.setColumns(25);
        mensajesTxt.setRows(5);

        jScrollPane1.setViewportView(mensajesTxt);

        getContentPane().add(jScrollPane1);
        jScrollPane1.setBounds(20, 160, 410, 70);

        setSize(new java.awt.Dimension(491, 290));
        setLocationRelativeTo(null);
    }// </editor-fold>

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new PrincipalSrv().setVisible(true);
            }
        });

    }
    private void bIniciarActionPerformed(java.awt.event.ActionEvent evt) {
        iniciarServidor();
    }

    private void iniciarServidor() {
        JOptionPane.showMessageDialog(this, "Iniciando servidor");
        // Hilo "aceptador": su única tarea es bloquearse en accept() y,
        // por cada cliente nuevo, delegar la conversación a un hilo propio
        // (ClienteHandler). Así el bucle vuelve enseguida a accept() y puede
        // admitir más usuarios sin esperar a que el primero termine.
        new Thread(new Runnable() {
            public void run() {
                try {
                    InetAddress addr = InetAddress.getLocalHost();
                    serverSocket = new ServerSocket(PORT);
                    appendMensaje("Servidor TCP en ejecución: " + addr + " ,Puerto " + serverSocket.getLocalPort());
                    while (true) {
                        Socket clientSocket = serverSocket.accept();
                        int id = contadorClientes.incrementAndGet();
                        ClienteHandler handler = new ClienteHandler(clientSocket, id);
                        clientesConectados.add(handler);
                        new Thread(handler).start();
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                    appendMensaje("Error en el servidor: " + ex.getMessage());
                }
            }
        }).start();
    }

    /**
     * Añade una línea al área de mensajes desde cualquier hilo, respetando
     * el hilo de eventos de Swing (EDT).
     */
    private void appendMensaje(String mensaje) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                mensajesTxt.append(mensaje + "\n");
            }
        });
    }

    /**
     * Reenvía un mensaje a todos los clientes conectados excepto al que lo
     * originó, para que cada usuario vea los mensajes del resto.
     */
    private void broadcast(String mensaje, ClienteHandler origen) {
        for (ClienteHandler cliente : clientesConectados) {
            if (cliente != origen) {
                cliente.enviar(mensaje);
            }
        }
    }

    /**
     * Representa la conexión con UN cliente. Cada instancia corre en su
     * propio hilo y mantiene su propio Socket/BufferedReader/PrintWriter,
     * en vez de compartir esos campos entre todos los clientes como hacía
     * la versión anterior (lo que causaba condiciones de carrera y solo
     * permitía un usuario a la vez).
     */
    private class ClienteHandler implements Runnable {
        private final Socket socket;
        private final int id;
        private PrintWriter out;

        ClienteHandler(Socket socket, int id) {
            this.socket = socket;
            this.id = id;
        }

        public void run() {
            String direccion = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                out = new PrintWriter(socket.getOutputStream(), true);
                // Primer mensaje que recibe el cliente: le informa su propio
                // número, para que su ventana pueda mostrarlo (ver PrincipalCli.conectar()).
                out.println(PREFIJO_ID_CLIENTE + id);
                appendMensaje("Cliente " + id + " conectado desde " + direccion);

                String linea;
                while ((linea = in.readLine()) != null) {
                    appendMensaje("Cliente " + id + ": " + linea);
                    out.println("Mensaje recibido en el servidor");
                    broadcast("Cliente " + id + ": " + linea, this);
                }
            } catch (IOException ex) {
                appendMensaje("Cliente " + id + " desconectado con error: " + ex.getMessage());
            } finally {
                desconectar();
            }
        }

        void enviar(String mensaje) {
            if (out != null) {
                out.println(mensaje);
            }
        }

        private void desconectar() {
            clientesConectados.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            appendMensaje("Cliente " + id + " desconectado. Usuarios conectados: " + clientesConectados.size());
        }
    }

    // Variables declaration - do not modify
    private javax.swing.JButton bIniciar;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JTextArea mensajesTxt;
    private javax.swing.JScrollPane jScrollPane1;
}
