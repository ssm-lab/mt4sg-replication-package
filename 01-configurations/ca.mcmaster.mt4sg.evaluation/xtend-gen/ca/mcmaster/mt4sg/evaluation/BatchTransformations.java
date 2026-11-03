package ca.mcmaster.mt4sg.evaluation;

import ca.mcmaster.mt4sg.models.scenegraph.Edge;
import ca.mcmaster.mt4sg.models.scenegraph.LaneSegment;
import ca.mcmaster.mt4sg.models.scenegraph.Node;
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraph;
import ca.mcmaster.mt4sg.models.scenegraph.SceneGraphModelPackage;
import ca.mcmaster.mt4sg.models.scenegraph.SemanticDistanceValue;
import ca.mcmaster.mt4sg.models.scenegraph.SemanticSpatialPositionValue;
import ca.mcmaster.mt4sg.models.scenegraph.Vehicle;
import ca.mcmaster.mt4sg.queries.DistanceEdgeToBeRemoved;
import ca.mcmaster.mt4sg.queries.FrontEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.FrontLeftEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.FrontRightEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.LateralRSSViolationEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.LateralRSSViolationEdgeToBeRemoved;
import ca.mcmaster.mt4sg.queries.LeftEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.LongitudinalRSSViolationEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.LongitudinalRSSViolationEdgeToBeRemoved;
import ca.mcmaster.mt4sg.queries.NearEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.PreCollisionEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.RearEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.RearLeftEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.RearRightEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.RightEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.Scenegraph;
import ca.mcmaster.mt4sg.queries.SuperNearEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.UpdateFront;
import ca.mcmaster.mt4sg.queries.UpdateFrontLeft;
import ca.mcmaster.mt4sg.queries.UpdateFrontRight;
import ca.mcmaster.mt4sg.queries.UpdateLeft;
import ca.mcmaster.mt4sg.queries.UpdateNear;
import ca.mcmaster.mt4sg.queries.UpdatePreCollision;
import ca.mcmaster.mt4sg.queries.UpdateRear;
import ca.mcmaster.mt4sg.queries.UpdateRearLeft;
import ca.mcmaster.mt4sg.queries.UpdateRearRight;
import ca.mcmaster.mt4sg.queries.UpdateRight;
import ca.mcmaster.mt4sg.queries.UpdateSuperNear;
import ca.mcmaster.mt4sg.queries.UpdateVeryNear;
import ca.mcmaster.mt4sg.queries.UpdateVisibleDistance;
import ca.mcmaster.mt4sg.queries.VehicleOnLaneSegmentEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.VehicleOnLaneSegmentEdgeToBeRemoved;
import ca.mcmaster.mt4sg.queries.VeryNearEdgeToBeAdded;
import ca.mcmaster.mt4sg.queries.VisibleDistanceEdgeToBeAdded;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.apache.log4j.Logger;
import org.eclipse.emf.common.command.CommandStack;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.viatra.query.runtime.api.AdvancedViatraQueryEngine;
import org.eclipse.viatra.query.runtime.emf.EMFScope;
import org.eclipse.viatra.transformation.runtime.emf.modelmanipulation.IModelManipulations;
import org.eclipse.viatra.transformation.runtime.emf.modelmanipulation.SimpleModelManipulations;
import org.eclipse.viatra.transformation.runtime.emf.rules.BatchTransformationRuleGroup;
import org.eclipse.viatra.transformation.runtime.emf.rules.batch.BatchTransformationRule;
import org.eclipse.viatra.transformation.runtime.emf.rules.batch.BatchTransformationRuleFactory;
import org.eclipse.viatra.transformation.runtime.emf.transformation.batch.BatchTransformation;
import org.eclipse.xtend2.lib.StringConcatenation;
import org.eclipse.xtext.xbase.lib.Exceptions;
import org.eclipse.xtext.xbase.lib.Extension;

/**
 * The rules of {@link ca.mcmaster.mt4sg.core.runner.Transformations} as a VIATRA batch
 * transformation, for the evaluation's batch strategies. The preconditions, actions, and model
 * manipulation helpers are copied from that class unchanged. The transformation runs on a query
 * engine of its own, which keeps the query results up to date with the model for as long as the
 * transformation lives, and
 * its rules fire only in {@link #fireWhilePossible}.
 */
@SuppressWarnings("all")
public class BatchTransformations {
  @Extension
  private Logger logger = Logger.getLogger(BatchTransformations.class);

  @Extension
  private BatchTransformationRuleFactory ruleFactory = new BatchTransformationRuleFactory();

  @Extension
  private IModelManipulations manipulation;

  @Extension
  private Scenegraph scenegraph = Scenegraph.instance();

  private final AdvancedViatraQueryEngine engine;

  private final BatchTransformation transformation;

  private final TransactionalEditingDomain domain;

  public BatchTransformations(final Resource resource) {
    EMFScope _eMFScope = new EMFScope(resource);
    this.engine = AdvancedViatraQueryEngine.createUnmanagedEngine(_eMFScope);
    this.domain = TransactionalEditingDomain.Factory.INSTANCE.getEditingDomain(resource.getResourceSet());
    SimpleModelManipulations _simpleModelManipulations = new SimpleModelManipulations(this.engine);
    this.manipulation = _simpleModelManipulations;
    this.transformation = BatchTransformation.forEngine(this.engine).addRules(this.getRules()).build();
  }

  /**
   * Runs {@code runnable} with the query result updates held back until it returns.
   */
  public void delayUpdatePropagation(final Runnable runnable) {
    try {
      this.engine.<Object>delayUpdatePropagation(new Callable<Object>() {
        @Override
        public Object call() throws Exception {
          runnable.run();
          return null;
        }
      });
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  /**
   * Fires the rules, 1 activation at a time, until none is enabled.
   */
  public void fireWhilePossible() {
    CommandStack _commandStack = this.domain.getCommandStack();
    _commandStack.execute(new RecordingCommand(this.domain) {
      @Override
      protected void doExecute() {
        BatchTransformations.this.transformation.getTransformationStatements().fireWhilePossible();
      }
    });
  }

  public BatchTransformationRuleGroup getRules() {
    final BatchTransformationRuleGroup rules = new BatchTransformationRuleGroup(
      this.createPreCollisionRule, 
      this.createSuperNearRule, 
      this.createVeryNearRule, 
      this.createNearRule, 
      this.createVisibleDistanceRule, 
      this.updatePreCollisionRule, 
      this.updateSuperNearRule, 
      this.updateVeryNearRule, 
      this.updateNearRule, 
      this.updateVisibleDistanceRule, 
      this.removeDistanceEdgeRule, 
      this.createLeftRule, 
      this.createFrontLeftRule, 
      this.createFrontRule, 
      this.createFrontRightRule, 
      this.createRightRule, 
      this.createRearRightRule, 
      this.createRearRule, 
      this.createRearLeftRule, 
      this.updateLeftRule, 
      this.updateFrontLeftRule, 
      this.updateFrontRule, 
      this.updateFrontRightRule, 
      this.updateRightRule, 
      this.updateRearRightRule, 
      this.updateRearRule, 
      this.updateRearLeftRule, 
      this.createVehicleOnLaneSegmentRule, 
      this.removeVehicleOnLaneSegmentRule, 
      this.createLongitudinalRSSViolationRule, 
      this.removeLongitudinalRSSViolationRule, 
      this.createLateralRSSViolationRule, 
      this.removeLateralRSSViolationRule);
    return rules;
  }

  private final BatchTransformationRule<PreCollisionEdgeToBeAdded.Match, PreCollisionEdgeToBeAdded.Matcher> createPreCollisionRule = this.ruleFactory.<PreCollisionEdgeToBeAdded.Match, PreCollisionEdgeToBeAdded.Matcher>createRule(this.scenegraph.getPreCollisionEdgeToBeAdded()).name("CreatePreCollisionRule").action(((Consumer<PreCollisionEdgeToBeAdded.Match>) (PreCollisionEdgeToBeAdded.Match it) -> {
    this.createDistanceEdge(it.getV1(), it.getV2(), SemanticDistanceValue.PRE_COLLISION);
  })).build();

  private final BatchTransformationRule<SuperNearEdgeToBeAdded.Match, SuperNearEdgeToBeAdded.Matcher> createSuperNearRule = this.ruleFactory.<SuperNearEdgeToBeAdded.Match, SuperNearEdgeToBeAdded.Matcher>createRule(this.scenegraph.getSuperNearEdgeToBeAdded()).name("CreateSuperNearRule").action(((Consumer<SuperNearEdgeToBeAdded.Match>) (SuperNearEdgeToBeAdded.Match it) -> {
    this.createDistanceEdge(it.getV1(), it.getV2(), SemanticDistanceValue.SUPER_NEAR);
  })).build();

  private final BatchTransformationRule<VeryNearEdgeToBeAdded.Match, VeryNearEdgeToBeAdded.Matcher> createVeryNearRule = this.ruleFactory.<VeryNearEdgeToBeAdded.Match, VeryNearEdgeToBeAdded.Matcher>createRule(this.scenegraph.getVeryNearEdgeToBeAdded()).name("CreateVeryNearRule").action(((Consumer<VeryNearEdgeToBeAdded.Match>) (VeryNearEdgeToBeAdded.Match it) -> {
    this.createDistanceEdge(it.getV1(), it.getV2(), SemanticDistanceValue.VERY_NEAR);
  })).build();

  private final BatchTransformationRule<NearEdgeToBeAdded.Match, NearEdgeToBeAdded.Matcher> createNearRule = this.ruleFactory.<NearEdgeToBeAdded.Match, NearEdgeToBeAdded.Matcher>createRule(this.scenegraph.getNearEdgeToBeAdded()).name("CreateNearRule").action(((Consumer<NearEdgeToBeAdded.Match>) (NearEdgeToBeAdded.Match it) -> {
    this.createDistanceEdge(it.getV1(), it.getV2(), SemanticDistanceValue.NEAR);
  })).build();

  private final BatchTransformationRule<VisibleDistanceEdgeToBeAdded.Match, VisibleDistanceEdgeToBeAdded.Matcher> createVisibleDistanceRule = this.ruleFactory.<VisibleDistanceEdgeToBeAdded.Match, VisibleDistanceEdgeToBeAdded.Matcher>createRule(this.scenegraph.getVisibleDistanceEdgeToBeAdded()).name("CreateVisibleDistanceRule").action(((Consumer<VisibleDistanceEdgeToBeAdded.Match>) (VisibleDistanceEdgeToBeAdded.Match it) -> {
    this.createDistanceEdge(it.getV1(), it.getV2(), SemanticDistanceValue.VISIBLE);
  })).build();

  private final BatchTransformationRule<UpdatePreCollision.Match, UpdatePreCollision.Matcher> updatePreCollisionRule = this.ruleFactory.<UpdatePreCollision.Match, UpdatePreCollision.Matcher>createRule(this.scenegraph.getUpdatePreCollision()).name("UpdatePreCollisionRule").action(((Consumer<UpdatePreCollision.Match>) (UpdatePreCollision.Match it) -> {
    this.updateDistance(it.getE(), SemanticDistanceValue.PRE_COLLISION);
  })).build();

  private final BatchTransformationRule<UpdateSuperNear.Match, UpdateSuperNear.Matcher> updateSuperNearRule = this.ruleFactory.<UpdateSuperNear.Match, UpdateSuperNear.Matcher>createRule(this.scenegraph.getUpdateSuperNear()).name("UpdateSuperNearRule").action(((Consumer<UpdateSuperNear.Match>) (UpdateSuperNear.Match it) -> {
    this.updateDistance(it.getE(), SemanticDistanceValue.SUPER_NEAR);
  })).build();

  private final BatchTransformationRule<UpdateVeryNear.Match, UpdateVeryNear.Matcher> updateVeryNearRule = this.ruleFactory.<UpdateVeryNear.Match, UpdateVeryNear.Matcher>createRule(this.scenegraph.getUpdateVeryNear()).name("UpdateVeryNearRule").action(((Consumer<UpdateVeryNear.Match>) (UpdateVeryNear.Match it) -> {
    this.updateDistance(it.getE(), SemanticDistanceValue.VERY_NEAR);
  })).build();

  private final BatchTransformationRule<UpdateNear.Match, UpdateNear.Matcher> updateNearRule = this.ruleFactory.<UpdateNear.Match, UpdateNear.Matcher>createRule(this.scenegraph.getUpdateNear()).name("UpdateNearRule").action(((Consumer<UpdateNear.Match>) (UpdateNear.Match it) -> {
    this.updateDistance(it.getE(), SemanticDistanceValue.NEAR);
  })).build();

  private final BatchTransformationRule<UpdateVisibleDistance.Match, UpdateVisibleDistance.Matcher> updateVisibleDistanceRule = this.ruleFactory.<UpdateVisibleDistance.Match, UpdateVisibleDistance.Matcher>createRule(this.scenegraph.getUpdateVisibleDistance()).name("UpdateVisibleDistanceRule").action(((Consumer<UpdateVisibleDistance.Match>) (UpdateVisibleDistance.Match it) -> {
    this.updateDistance(it.getE(), SemanticDistanceValue.VISIBLE);
  })).build();

  private final BatchTransformationRule<DistanceEdgeToBeRemoved.Match, DistanceEdgeToBeRemoved.Matcher> removeDistanceEdgeRule = this.ruleFactory.<DistanceEdgeToBeRemoved.Match, DistanceEdgeToBeRemoved.Matcher>createRule(this.scenegraph.getDistanceEdgeToBeRemoved()).name("RemoveEdgeRule").action(((Consumer<DistanceEdgeToBeRemoved.Match>) (DistanceEdgeToBeRemoved.Match it) -> {
    this.deleteEdge(it.getE());
  })).build();

  private final BatchTransformationRule<LeftEdgeToBeAdded.Match, LeftEdgeToBeAdded.Matcher> createLeftRule = this.ruleFactory.<LeftEdgeToBeAdded.Match, LeftEdgeToBeAdded.Matcher>createRule(this.scenegraph.getLeftEdgeToBeAdded()).name("CreateLeftRule").action(((Consumer<LeftEdgeToBeAdded.Match>) (LeftEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.LEFT);
  })).build();

  private final BatchTransformationRule<FrontLeftEdgeToBeAdded.Match, FrontLeftEdgeToBeAdded.Matcher> createFrontLeftRule = this.ruleFactory.<FrontLeftEdgeToBeAdded.Match, FrontLeftEdgeToBeAdded.Matcher>createRule(this.scenegraph.getFrontLeftEdgeToBeAdded()).name("CreateFrontLeftRule").action(((Consumer<FrontLeftEdgeToBeAdded.Match>) (FrontLeftEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.FRONT_LEFT);
  })).build();

  private final BatchTransformationRule<FrontEdgeToBeAdded.Match, FrontEdgeToBeAdded.Matcher> createFrontRule = this.ruleFactory.<FrontEdgeToBeAdded.Match, FrontEdgeToBeAdded.Matcher>createRule(this.scenegraph.getFrontEdgeToBeAdded()).name("CreateFrontRule").action(((Consumer<FrontEdgeToBeAdded.Match>) (FrontEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.FRONT);
  })).build();

  private final BatchTransformationRule<FrontRightEdgeToBeAdded.Match, FrontRightEdgeToBeAdded.Matcher> createFrontRightRule = this.ruleFactory.<FrontRightEdgeToBeAdded.Match, FrontRightEdgeToBeAdded.Matcher>createRule(this.scenegraph.getFrontRightEdgeToBeAdded()).name("CreateFrontRightRule").action(((Consumer<FrontRightEdgeToBeAdded.Match>) (FrontRightEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.FRONT_RIGHT);
  })).build();

  private final BatchTransformationRule<RightEdgeToBeAdded.Match, RightEdgeToBeAdded.Matcher> createRightRule = this.ruleFactory.<RightEdgeToBeAdded.Match, RightEdgeToBeAdded.Matcher>createRule(this.scenegraph.getRightEdgeToBeAdded()).name("CreateRightRule").action(((Consumer<RightEdgeToBeAdded.Match>) (RightEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.RIGHT);
  })).build();

  private final BatchTransformationRule<RearRightEdgeToBeAdded.Match, RearRightEdgeToBeAdded.Matcher> createRearRightRule = this.ruleFactory.<RearRightEdgeToBeAdded.Match, RearRightEdgeToBeAdded.Matcher>createRule(this.scenegraph.getRearRightEdgeToBeAdded()).name("CreateRearRightRule").action(((Consumer<RearRightEdgeToBeAdded.Match>) (RearRightEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.REAR_RIGHT);
  })).build();

  private final BatchTransformationRule<RearEdgeToBeAdded.Match, RearEdgeToBeAdded.Matcher> createRearRule = this.ruleFactory.<RearEdgeToBeAdded.Match, RearEdgeToBeAdded.Matcher>createRule(this.scenegraph.getRearEdgeToBeAdded()).name("CreateRearRule").action(((Consumer<RearEdgeToBeAdded.Match>) (RearEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.REAR);
  })).build();

  private final BatchTransformationRule<RearLeftEdgeToBeAdded.Match, RearLeftEdgeToBeAdded.Matcher> createRearLeftRule = this.ruleFactory.<RearLeftEdgeToBeAdded.Match, RearLeftEdgeToBeAdded.Matcher>createRule(this.scenegraph.getRearLeftEdgeToBeAdded()).name("CreateRearLeftRule").action(((Consumer<RearLeftEdgeToBeAdded.Match>) (RearLeftEdgeToBeAdded.Match it) -> {
    this.createSpatialEdge(it.getSource(), it.getTarget(), SemanticSpatialPositionValue.REAR_LEFT);
  })).build();

  private final BatchTransformationRule<UpdateLeft.Match, UpdateLeft.Matcher> updateLeftRule = this.ruleFactory.<UpdateLeft.Match, UpdateLeft.Matcher>createRule(this.scenegraph.getUpdateLeft()).name("UpdateLeftRule").action(((Consumer<UpdateLeft.Match>) (UpdateLeft.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.LEFT);
  })).build();

  private final BatchTransformationRule<UpdateFrontLeft.Match, UpdateFrontLeft.Matcher> updateFrontLeftRule = this.ruleFactory.<UpdateFrontLeft.Match, UpdateFrontLeft.Matcher>createRule(this.scenegraph.getUpdateFrontLeft()).name("UpdateFrontLeftRule").action(((Consumer<UpdateFrontLeft.Match>) (UpdateFrontLeft.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.FRONT_LEFT);
  })).build();

  private final BatchTransformationRule<UpdateFront.Match, UpdateFront.Matcher> updateFrontRule = this.ruleFactory.<UpdateFront.Match, UpdateFront.Matcher>createRule(this.scenegraph.getUpdateFront()).name("UpdateFrontRule").action(((Consumer<UpdateFront.Match>) (UpdateFront.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.FRONT);
  })).build();

  private final BatchTransformationRule<UpdateFrontRight.Match, UpdateFrontRight.Matcher> updateFrontRightRule = this.ruleFactory.<UpdateFrontRight.Match, UpdateFrontRight.Matcher>createRule(this.scenegraph.getUpdateFrontRight()).name("UpdateFrontRightRule").action(((Consumer<UpdateFrontRight.Match>) (UpdateFrontRight.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.FRONT_RIGHT);
  })).build();

  private final BatchTransformationRule<UpdateRight.Match, UpdateRight.Matcher> updateRightRule = this.ruleFactory.<UpdateRight.Match, UpdateRight.Matcher>createRule(this.scenegraph.getUpdateRight()).name("UpdateRightRule").action(((Consumer<UpdateRight.Match>) (UpdateRight.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.RIGHT);
  })).build();

  private final BatchTransformationRule<UpdateRearRight.Match, UpdateRearRight.Matcher> updateRearRightRule = this.ruleFactory.<UpdateRearRight.Match, UpdateRearRight.Matcher>createRule(this.scenegraph.getUpdateRearRight()).name("UpdateRearRightRule").action(((Consumer<UpdateRearRight.Match>) (UpdateRearRight.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.REAR_RIGHT);
  })).build();

  private final BatchTransformationRule<UpdateRear.Match, UpdateRear.Matcher> updateRearRule = this.ruleFactory.<UpdateRear.Match, UpdateRear.Matcher>createRule(this.scenegraph.getUpdateRear()).name("UpdateRearRule").action(((Consumer<UpdateRear.Match>) (UpdateRear.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.REAR);
  })).build();

  private final BatchTransformationRule<UpdateRearLeft.Match, UpdateRearLeft.Matcher> updateRearLeftRule = this.ruleFactory.<UpdateRearLeft.Match, UpdateRearLeft.Matcher>createRule(this.scenegraph.getUpdateRearLeft()).name("UpdateRearLeftRule").action(((Consumer<UpdateRearLeft.Match>) (UpdateRearLeft.Match it) -> {
    this.updateSpatial(it.getE(), SemanticSpatialPositionValue.REAR_LEFT);
  })).build();

  private final BatchTransformationRule<VehicleOnLaneSegmentEdgeToBeAdded.Match, VehicleOnLaneSegmentEdgeToBeAdded.Matcher> createVehicleOnLaneSegmentRule = this.ruleFactory.<VehicleOnLaneSegmentEdgeToBeAdded.Match, VehicleOnLaneSegmentEdgeToBeAdded.Matcher>createRule(this.scenegraph.getVehicleOnLaneSegmentEdgeToBeAdded()).name("CreateVehicleOnLaneSegmentRule").action(((Consumer<VehicleOnLaneSegmentEdgeToBeAdded.Match>) (VehicleOnLaneSegmentEdgeToBeAdded.Match it) -> {
    this.createVehicleIsInLaneSegmentEdge(it.getVehicle(), it.getLane());
  })).build();

  private final BatchTransformationRule<VehicleOnLaneSegmentEdgeToBeRemoved.Match, VehicleOnLaneSegmentEdgeToBeRemoved.Matcher> removeVehicleOnLaneSegmentRule = this.ruleFactory.<VehicleOnLaneSegmentEdgeToBeRemoved.Match, VehicleOnLaneSegmentEdgeToBeRemoved.Matcher>createRule(this.scenegraph.getVehicleOnLaneSegmentEdgeToBeRemoved()).name("RemoveVehicleOnLaneSegmentRule").action(((Consumer<VehicleOnLaneSegmentEdgeToBeRemoved.Match>) (VehicleOnLaneSegmentEdgeToBeRemoved.Match it) -> {
    this.deleteEdge(it.getE());
  })).build();

  private final BatchTransformationRule<LongitudinalRSSViolationEdgeToBeAdded.Match, LongitudinalRSSViolationEdgeToBeAdded.Matcher> createLongitudinalRSSViolationRule = this.ruleFactory.<LongitudinalRSSViolationEdgeToBeAdded.Match, LongitudinalRSSViolationEdgeToBeAdded.Matcher>createRule(this.scenegraph.getLongitudinalRSSViolationEdgeToBeAdded()).name("CreateLongitudinalRSSViolationRule").action(((Consumer<LongitudinalRSSViolationEdgeToBeAdded.Match>) (LongitudinalRSSViolationEdgeToBeAdded.Match it) -> {
    this.createTypedEdge(it.getV1(), it.getV2(), SceneGraphModelPackage.Literals.LONGITUDINAL_RSS_VIOLATION);
  })).build();

  private final BatchTransformationRule<LongitudinalRSSViolationEdgeToBeRemoved.Match, LongitudinalRSSViolationEdgeToBeRemoved.Matcher> removeLongitudinalRSSViolationRule = this.ruleFactory.<LongitudinalRSSViolationEdgeToBeRemoved.Match, LongitudinalRSSViolationEdgeToBeRemoved.Matcher>createRule(this.scenegraph.getLongitudinalRSSViolationEdgeToBeRemoved()).name("RemoveLongitudinalRSSViolationRule").action(((Consumer<LongitudinalRSSViolationEdgeToBeRemoved.Match>) (LongitudinalRSSViolationEdgeToBeRemoved.Match it) -> {
    this.deleteEdge(it.getE());
  })).build();

  private final BatchTransformationRule<LateralRSSViolationEdgeToBeAdded.Match, LateralRSSViolationEdgeToBeAdded.Matcher> createLateralRSSViolationRule = this.ruleFactory.<LateralRSSViolationEdgeToBeAdded.Match, LateralRSSViolationEdgeToBeAdded.Matcher>createRule(this.scenegraph.getLateralRSSViolationEdgeToBeAdded()).name("CreateLateralRSSViolationRule").action(((Consumer<LateralRSSViolationEdgeToBeAdded.Match>) (LateralRSSViolationEdgeToBeAdded.Match it) -> {
    this.createTypedEdge(it.getV1(), it.getV2(), SceneGraphModelPackage.Literals.LATERAL_RSS_VIOLATION);
  })).build();

  private final BatchTransformationRule<LateralRSSViolationEdgeToBeRemoved.Match, LateralRSSViolationEdgeToBeRemoved.Matcher> removeLateralRSSViolationRule = this.ruleFactory.<LateralRSSViolationEdgeToBeRemoved.Match, LateralRSSViolationEdgeToBeRemoved.Matcher>createRule(this.scenegraph.getLateralRSSViolationEdgeToBeRemoved()).name("RemoveLateralRSSViolationRule").action(((Consumer<LateralRSSViolationEdgeToBeRemoved.Match>) (LateralRSSViolationEdgeToBeRemoved.Match it) -> {
    this.deleteEdge(it.getE());
  })).build();

  public void createDistanceEdge(final Vehicle v1, final Vehicle v2, final SemanticDistanceValue distance) {
    try {
      EObject _eContainer = v1.eContainer();
      final SceneGraph scene = ((SceneGraph) _eContainer);
      EObject _createChild = this.manipulation.createChild(scene, SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES, 
        SceneGraphModelPackage.Literals.DISTANCE);
      final Edge edge = ((Edge) _createChild);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__SOURCE, v1);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__TARGET, v2);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.DISTANCE__VALUE, distance);
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Created DistanceEdge: ");
      String _id = v1.getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = v2.getId();
      _builder.append(_id_1);
      _builder.append(" (");
      _builder.append(distance);
      _builder.append(")");
      this.logger.info(_builder);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  public void createSpatialEdge(final Vehicle source, final Vehicle target, final SemanticSpatialPositionValue spatial) {
    try {
      EObject _eContainer = source.eContainer();
      final SceneGraph scene = ((SceneGraph) _eContainer);
      EObject _createChild = this.manipulation.createChild(scene, SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES, 
        SceneGraphModelPackage.Literals.SPATIAL_POSITION);
      final Edge edge = ((Edge) _createChild);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__SOURCE, source);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__TARGET, target);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.SPATIAL_POSITION__VALUE, spatial);
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Created SpatialEdge: ");
      String _id = source.getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = target.getId();
      _builder.append(_id_1);
      _builder.append(" (");
      _builder.append(spatial);
      _builder.append(")");
      this.logger.info(_builder);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  public void createVehicleIsInLaneSegmentEdge(final Vehicle vehicle, final LaneSegment lane) {
    try {
      EObject _eContainer = vehicle.eContainer();
      final SceneGraph scene = ((SceneGraph) _eContainer);
      EObject _createChild = this.manipulation.createChild(scene, SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES, 
        SceneGraphModelPackage.Literals.VEHICLE_IN_LANE_SEGMENT);
      final Edge edge = ((Edge) _createChild);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__SOURCE, vehicle);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__TARGET, lane);
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Created VehicleIsInLaneSegmentEdge: ");
      String _id = vehicle.getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = lane.getId();
      _builder.append(_id_1);
      this.logger.info(_builder);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  public void createTypedEdge(final Node source, final Node target, final EClass edgeType) {
    try {
      EObject _eContainer = source.eContainer();
      final SceneGraph scene = ((SceneGraph) _eContainer);
      EObject _createChild = this.manipulation.createChild(scene, SceneGraphModelPackage.Literals.SCENE_GRAPH__EDGES, edgeType);
      final Edge edge = ((Edge) _createChild);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__SOURCE, source);
      this.manipulation.set(edge, SceneGraphModelPackage.Literals.EDGE__TARGET, target);
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Created ");
      String _name = edgeType.getName();
      _builder.append(_name);
      _builder.append(": ");
      String _id = source.getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = target.getId();
      _builder.append(_id_1);
      this.logger.info(_builder);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  public void updateDistance(final Edge e, final SemanticDistanceValue distance) {
    try {
      this.manipulation.set(e, SceneGraphModelPackage.Literals.DISTANCE__VALUE, distance);
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Updated distance: ");
      String _id = e.getSource().getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = e.getTarget().getId();
      _builder.append(_id_1);
      _builder.append(" = ");
      _builder.append(distance);
      this.logger.info(_builder);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  public void updateSpatial(final Edge e, final SemanticSpatialPositionValue spatial) {
    try {
      this.manipulation.set(e, SceneGraphModelPackage.Literals.SPATIAL_POSITION__VALUE, spatial);
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Updated spatial: ");
      String _id = e.getSource().getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = e.getTarget().getId();
      _builder.append(_id_1);
      _builder.append(" = ");
      _builder.append(spatial);
      this.logger.info(_builder);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }

  public void deleteEdge(final Edge e) {
    try {
      StringConcatenation _builder = new StringConcatenation();
      _builder.append("Removed ");
      String _name = e.eClass().getName();
      _builder.append(_name);
      _builder.append(" edge: ");
      String _id = e.getSource().getId();
      _builder.append(_id);
      _builder.append(" -> ");
      String _id_1 = e.getTarget().getId();
      _builder.append(_id_1);
      this.logger.info(_builder);
      this.manipulation.remove(e);
    } catch (Throwable _e) {
      throw Exceptions.sneakyThrow(_e);
    }
  }
}
