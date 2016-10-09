package hex.deepwater;

import deepwater.backends.BackendModel;
import deepwater.backends.BackendParams;
import deepwater.backends.BackendTrain;
import deepwater.backends.RuntimeOptions;
import deepwater.datasets.ImageDataSet;
import hex.DataInfo;
import hex.Model;
import water.H2O;
import water.Iced;
import water.Key;
import water.exceptions.H2OIllegalArgumentException;
import water.util.Log;
import water.util.PrettyPrint;
import water.util.TwoDimTable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static hex.genmodel.algos.DeepWaterMojo.createDeepWaterBackend;


/**
 * This class contains the state of the Deep Learning model
 * This will be shared: one per node
 */
final public class DeepWaterModelInfo extends Iced {
  int _classes;
  int _deviceID;
  boolean _gpu;
  byte[] _network; // model definition (graph)
  byte[] _modelparams; // internal state of native backend (weights/biases/helpers)

  public TwoDimTable summaryTable;

  //for image classification
  transient BackendTrain _backend; //interface provider
  transient BackendModel _model;  //pointer to C++ process

  int _height;
  int _width;
  int _channels;
  float[] _meanData; //mean pixel value of the training data

  //for numeric classification (csv/frame style)
  int _ncols;
  Key<DataInfo> _dataInfoKey;

  public void nukeBackend() {
    if (_backend != null && _model != null) {
      _backend.delete(_model);
    }
    _backend = null;
    _model = null;
  }

  public void saveNativeState(String path, int iteration) {
    assert(_backend !=null);
    assert(_model!=null);
    _backend.saveModel(_model, path + ".json"); //independent of iterations
    _backend.saveParam(_model, path + "." + iteration + ".params");
  }

  float[] predict(float[] data) {
    assert(_backend !=null);
    assert(_model!=null);
    return _backend.predict(_model, data);
  }

  @Override
  public int hashCode() {
    return _network.hashCode() + _modelparams.hashCode();
  }

  // compute model size (number of model parameters required for making predictions)
  // momenta are not counted here, but they are needed for model building
  public long size() {
    long res = 0;
    if (_network!=null) res+=_network.length;
    if (_modelparams!=null) res+=_modelparams.length;
    return res;
  }

  Key<Model> _model_id;
  public DeepWaterParameters parameters;
  public final DeepWaterParameters get_params() { return parameters; }

  private long processed_global;
  public synchronized long get_processed_global() { return processed_global; }
  public synchronized void set_processed_global(long p) { processed_global = p; }
  public synchronized void add_processed_global(long p) { processed_global += p; }
  private long processed_local;
  public synchronized long get_processed_local() { return processed_local; }
  public synchronized void set_processed_local(long p) { processed_local = p; }
  public synchronized void add_processed_local(long p) { processed_local += p; }
  public synchronized long get_processed_total() { return processed_global + processed_local; }

  final boolean _classification; // Classification cache (nclasses>1)

  private RuntimeOptions getRuntimeOptions() {
    RuntimeOptions opts = new RuntimeOptions();
    opts.setSeed((int) get_params().getOrMakeRealSeed());
    opts.setUseGPU(get_params()._gpu);
    opts.setDeviceID(get_params()._device_id);
    return opts;
  }

  private BackendParams getBackendParams() {
    BackendParams backendParams = new BackendParams();
    backendParams.set("mini_batch_size", get_params()._mini_batch_size);
    backendParams.set("clip_gradient", get_params()._clip_gradient);
    String network = parameters._network == null ? null : parameters._network.toString();
    if (network==null) {
      assert (parameters._activation != null);
      assert (parameters._hidden != null);
      String[] acts = new String[parameters._hidden.length];
      String acti;
      if (parameters._activation.toString().startsWith("Rectifier")) acti = "relu";
      else if (parameters._activation.toString().startsWith("Tanh")) acti = "tanh";
      else throw H2O.unimpl();
      Arrays.fill(acts, acti);
      backendParams.set("activations", acts);
      backendParams.set("hidden", parameters._hidden);
      backendParams.set("input_dropout_ratio", parameters._input_dropout_ratio);
      backendParams.set("hidden_dropout_ratios", parameters._hidden_dropout_ratios);
    }
    return backendParams;
  }

  private ImageDataSet getImageDataSet() {
    ImageDataSet dataset = new ImageDataSet(_width, _height, _channels);
    if (parameters._mean_image_file != null) {
      final File f = new File(parameters._mean_image_file);
      if (f.exists() && !f.isDirectory()) {
        Log.info("Loading the mean image data from: " + f);
        float[] meanData = _backend.loadMeanImage(_model, f.getAbsolutePath());
        if (meanData.length > 0)
          dataset.setMeanData(meanData);
      }
      else {
        System.err.println("Mean image file " + f + " not found.");
      }
    } else if (get_params()._problem_type == DeepWaterParameters.ProblemType.image_classification) {
      Log.warn("No mean image file specified. Using 0 values. Convergence might be slower.");
    }
    return dataset;
  }

  /**
   * Main constructor
   * @param params Model parameters
   * @param nClasses number of classes (1 for regression, 0 for autoencoder)
   */
  public DeepWaterModelInfo(final DeepWaterParameters params, Key model_id, int nClasses, int nFeatures) {
    _ncols = nFeatures;
    _classes = nClasses;
    _classification = _classes > 1;
    parameters = (DeepWaterParameters) params.clone(); //make a copy, don't change model's parameters
    _model_id = model_id;
    DeepWaterParameters.Sanity.modifyParms(parameters, parameters, _classes); //sanitize the model_info's parameters
    _deviceID=parameters._device_id;
    _gpu=parameters._gpu;

    if (parameters._checkpoint!=null) {
      try {
        DeepWaterModel other = (DeepWaterModel) parameters._checkpoint.get();
        javaToNative(other.model_info()._network, other.model_info()._modelparams);
        throw H2O.unimpl();
      } catch (Throwable t) {
        throw new H2OIllegalArgumentException("Invalid checkpoint provided.");
      }
    }
    else {
      _width = _ncols;
      _height = 0;
      _channels = 0;
      if (parameters._problem_type == DeepWaterParameters.ProblemType.image_classification) {
        _width=parameters._image_shape[0];
        _height=parameters._image_shape[1];
        _channels=parameters._channels;
        if (_width==0 || _height==0) {
          switch(parameters._network) {
            case lenet:
              _width = 28;
              _height = 28;
              break;
            case auto:
            case alexnet:
            case inception_bn:
            case googlenet:
            case resnet:
              _width = 224;
              _height = 224;
              break;
            case vgg:
              _width = 320;
              _height = 320;
              break;
            case user:
              throw new H2OIllegalArgumentException("Please specify width and height for user-given model definition.");
            default:
              throw H2O.unimpl("Unknown network type: " + parameters._network);
          }
        }
        assert(_width>0);
        assert(_height>0);
      } else if (parameters._problem_type == DeepWaterParameters.ProblemType.h2oframe_classification) {
        if (parameters._image_shape != null) {
          if (parameters._image_shape[0]>0)
            _width = parameters._image_shape[0];
          if (parameters._image_shape[1]>0)
            _height = parameters._image_shape[1];
          if (_width>0 && _height>0)
            _channels = parameters._channels;
          else
            _channels = 0;
        }
      } else {
        Log.warn("unknown problem_type:", parameters._problem_type);
        throw H2O.unimpl();
      }

      try {
        _backend = createDeepWaterBackend(parameters._backend.toString()); // new ImageTrain(_width, _height, _channels, _deviceID, (int)parameters.getOrMakeRealSeed(), _gpu);
        if (_backend == null) throw new IllegalArgumentException("No backend found. Cannot build a Deep Water model.");
        ImageDataSet imageDataSet = getImageDataSet();
        _meanData = imageDataSet.getMeanData();
        RuntimeOptions opts = getRuntimeOptions();
        BackendParams bparms = getBackendParams();
        if (parameters._network != DeepWaterParameters.Network.user) {
          String network = parameters._network == null ? null : parameters._network.toString();
          if (network != null && parameters._network != DeepWaterParameters.Network.user) {
            Log.info("Creating a fresh model of the following network type: " + network);
            _model = _backend.buildNet(imageDataSet, opts, bparms, _classes, network);
          } else {
            Log.info("Creating a fresh model of the following network type: MLP");
            _model = _backend.buildNet(imageDataSet, opts, bparms, _classes, "MLP");
          }
        }

        // load a network if specified
        final String networkDef = parameters._network_definition_file;
        if (networkDef != null && !networkDef.isEmpty()) {
          File f = new File(networkDef);
          if(!f.exists() || f.isDirectory()) {
            Log.err("Network definition file " + f + " not found.");
          } else {
            Log.info("Loading the network from: " + f.getAbsolutePath());
            Log.info("Setting the optimizer and initializing the first and last layer.");
            _model = _backend.buildNet(imageDataSet, opts, bparms, _classes, f.getAbsolutePath());
          }
        }

        final String networkParms = parameters._network_parameters_file;
        if (networkParms != null && !networkParms.isEmpty()) {
          File f = new File(networkParms);
          if(!f.exists() || f.isDirectory()) {
            Log.err("Parameter file " + f + " not found.");
          } else {
            Log.info("Loading the parameters (weights/biases) from: " + f.getAbsolutePath());
            assert (_model != null);
            _backend.loadParam(_model, f.getAbsolutePath());
          }
        } else {
          Log.warn("No network parameters file specified. Starting from scratch.");
        }


        nativeToJava(); //store initial state as early as it's created
      } catch(Throwable t) {
        Log.err("Unable to initialize the native Deep Learning backend: " + t.getMessage());
        throw t;
      }
    }
  }

  public void nativeToJava() {
    if (_backend ==null) return;
    Log.info("Native backend -> Java.");
    long now = System.currentTimeMillis();
    Path path = null;
    // only overwrite the network definition if it's null
    if (_network==null) {
      try {
        path = Paths.get(System.getProperty("java.io.tmpdir"), Key.make().toString());
        Log.info("backend is saving the model architecture.");
        _backend.saveModel(_model, path.toString());
        Log.info("done.");
        _network = Files.readAllBytes(path);
      } catch (IOException e) {
        e.printStackTrace();
      } finally { if (path!=null) path.toFile().delete(); }
    }
    // always overwrite the parameters (weights/biases)
    try {
      path = Paths.get(System.getProperty("java.io.tmpdir"), Key.make().toString());
      Log.info("backend is saving the parameters.");
      _backend.saveParam(_model, path.toString());
      Log.info("done.");
      _modelparams = Files.readAllBytes(path);
    } catch (IOException e) {
      e.printStackTrace();
    } finally { if (path!=null) path.toFile().delete(); }
    long time = System.currentTimeMillis() - now;
    Log.info("Took: " + PrettyPrint.msecs(time, true));
  }

  /**
   * Create native backend and fill it with the model's state stored in the Java model
   */
  public void javaToNative() {
    javaToNative(null,null);
  }

  /**
   * Internal helper to create a native backend, and fill its state
   * @param network user-given network topology
   * @param parameters user-given network state (weights/biases)
   */
  private void javaToNative(byte[] network, byte[] parameters) {
    long now = System.currentTimeMillis();
    //existing state is fine
    if (_backend !=null
            // either not overwriting with user-given (new) state, or we already are in sync
            && (network == null || network.equals(_network))
            && (parameters == null || Arrays.equals(parameters,_modelparams))) {
      Log.warn("No need to move the state from Java to native.");
      return;
    }
    if (_backend ==null) {
      _backend = createDeepWaterBackend(get_params()._backend.toString()); // new ImageTrain(_width, _height, _channels, _deviceID, (int)parameters.getOrMakeRealSeed(), _gpu);
      if (_backend == null) throw new IllegalArgumentException("No backend found. Cannot build a Deep Water model.");
    }

    if (network==null) network = _network;
    if (parameters==null) parameters= _modelparams;
    if (network==null || parameters==null) return;
    Log.info("Java state -> native backend.");

    Path path = null;
    // only overwrite the network definition if it's null
    try {
      path = Paths.get(System.getProperty("java.io.tmpdir"), Key.make().toString() + ".json");
      Files.write(path, network);
      Log.info("Randomizing everything.");
      _model = _backend.buildNet(getImageDataSet(), getRuntimeOptions(), getBackendParams(), _classes, path.toString()); //randomizing initial state
    } catch (IOException e) {
      e.printStackTrace();
    } finally { if (path!=null) path.toFile().delete(); }
    // always overwrite the parameters (weights/biases)
    try {
      path = Paths.get(System.getProperty("java.io.tmpdir"), Key.make().toString());
      Files.write(path, parameters);
      _backend.loadParam(_model, path.toString());
    } catch (IOException e) {
      e.printStackTrace();
    } finally { if (path!=null) path.toFile().delete(); }

    long time = System.currentTimeMillis() - now;
    Log.info("Took: " + PrettyPrint.msecs(time, true));
  }

  /**
   * Create a summary table
   * @return TwoDimTable with the summary of the model
   */
  TwoDimTable createSummaryTable() {
    TwoDimTable table = new TwoDimTable(
        "Status of Deep Learning Model",
        get_params()._network == null ? "MLP" : get_params()._network.toString() + ": " + PrettyPrint.bytes(size()) + ", "
        + (!get_params()._autoencoder ? ("predicting " + get_params()._response_column + ", ") : "") +
            (get_params()._autoencoder ? "auto-encoder" :
                _classification ? (_classes + "-class classification") : "regression")
            + ", "
            + String.format("%,d", get_processed_global()) + " training samples, "
            + "mini-batch size " + String.format("%,d", get_params()._mini_batch_size),
        new String[1], //rows
        new String[]{"Rate", "Momentum" },
        new String[]{"double", "double" },
        new String[]{"%5f", "%5f"},
        "");

    table.set(0, 0, get_params().learningRate(get_processed_global()));
    table.set(0, 1, get_params().momentum(get_processed_global()));
    summaryTable = table;
    return summaryTable;
  }

  /**
   * Print a summary table
   * @return String containing ASCII version of summary table
   */
  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    if (!get_params()._quiet_mode) {
      createSummaryTable();
      if (summaryTable!=null) sb.append(summaryTable.toString(1));
    }
    return sb.toString();
  }

  /**
   * Debugging printout
   * @return String with useful info
   */
  public String toStringAll() {
    StringBuilder sb = new StringBuilder();
    sb.append(toString());
    sb.append("\nprocessed global: ").append(get_processed_global());
    sb.append("\nprocessed local:  ").append(get_processed_local());
    sb.append("\nprocessed total:  ").append(get_processed_total());
    sb.append("\n");
    return sb.toString();
  }
  public void add(DeepWaterModelInfo other) {
    throw H2O.unimpl();
  }
  public void mult(double N) {
    throw H2O.unimpl();
  }
  public void div(double N) {
    throw H2O.unimpl();
  }
}
