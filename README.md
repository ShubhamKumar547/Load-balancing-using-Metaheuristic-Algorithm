PSO-Based Cloud Load Balancing

This project implements Particle Swarm Optimization (PSO) for cloud load balancing using CloudSim Plus and compares its performance with Round Robin, Random, and Least Loaded algorithms.

The simulation generates CSV results, which are visualized through HTML charts for comparative performance analysis.

How to Run

Step 1: Generate simulation results

mvn exec:java "-Dexec.mainClass=com.pso.Main"

This runs the simulation and generates results.csv.

Step 2: Generate comparative analysis charts

mvn exec:java "-Dexec.mainClass=com.pso.HtmlChartGenerator" "-Dexec.args=results.csv results/comparative-analysis.html"

This generates the HTML report at results/comparative-analysis.html.

Step 3: View the results

Open results/comparative-analysis.html in your web browser to view the comparative charts.

Note: Always run Step 1 before Step 2 to ensure the CSV file exists.
