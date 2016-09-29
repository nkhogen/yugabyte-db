import React, { Component, PropTypes } from 'react';
import { Modal } from 'react-bootstrap';
import YBInput from '../components/YBInputField';
import YBSelect from './fields/YBSelect';
import YBCheckBox from './fields/YBCheckBox';
import YBMultiSelect from './fields/YBMultiSelect';
import YBNumericInput from './fields/YBNumericInput';
import { Field } from 'redux-form';

export default class UniverseModal extends Component {

  static propTypes = {
    type: PropTypes.oneOf(['Edit', 'Create']).isRequired,
  }

  constructor(props) {
    super(props);
    this.providerChanged = this.providerChanged.bind(this);
    this.regionListChanged = this.regionListChanged.bind(this);
    this.instanceTypeChanged = this.instanceTypeChanged.bind(this);
    this.numNodesChanged = this.numNodesChanged.bind(this);
    this.submitCreateUniverse = this.props.submitCreateUniverse.bind(this);
    this.submitEditUniverse = this.props.submitEditUniverse.bind(this);
    this.state = { providerSelected: '',
      regionSelected: [], instanceTypeSelected: '',
      numNodes: 3, azCheckState: true};
  }

  componentWillMount() {
    this.props.getProviderListItems();
    if(this.props.type === "Edit") {
      var providerUUID = this.props.universe.currentUniverse.provider.uuid;
      var azState = this.props.universe.currentUniverse.universeDetails.userIntent.isMultiAZ;
      this.setState({providerSelected: providerUUID});
      this.setState({instanceTypeSelected: this.props.universe.currentUniverse.universeDetails.userIntent.instanceType});
      this.props.getRegionListItems(providerUUID, azState);
      this.props.getInstanceTypeListItems(providerUUID);
      this.setState({instanceTypeSelected: "m3.medium"});
    }
  }

  componentWillUnmount() {
    this.props.resetProviderList();
  }

  providerChanged(event) {

    var providerUUID = event.target.value;
    this.setState({providerSelected: providerUUID});
    this.props.getRegionListItems(providerUUID, this.props.selectedAzState);
    this.props.getInstanceTypeListItems(providerUUID);
  }

  regionListChanged(value) {
    this.setState({regionSelected: value});
  }

  instanceTypeChanged(event) {
    this.setState({instanceTypeSelected: event.target.value});
  }

  numNodesChanged(event) {
    this.setState({numNodes: event.target.value});
  }

  render() {
    var self = this;
    const {visible, handleSubmit,
      onClose } = this.props;

    var azCheckStateChanged =function() {
      self.setState({azCheckState: !self.state.azCheckState});
    }

    var universeProviderList = this.props.cloud.providers.map(function(providerItem, idx) {
      return <option key={providerItem.uuid} value={providerItem.uuid}>
              {providerItem.name}
             </option>;
    });
    universeProviderList.unshift(<option key="" value=""></option>);


    var universeRegionList = this.props.cloud.regions.map(function (regionItem, idx) {
      return {value: regionItem.uuid, label: regionItem.name};
    });

    var universeInstanceTypeList =
      this.props.cloud.instanceTypes.map(function (instanceTypeItem, idx) {
        return <option key={instanceTypeItem.instanceTypeCode}
                       value={instanceTypeItem.instanceTypeCode}>
                 {instanceTypeItem.instanceTypeCode}
               </option>
      });
    if(universeInstanceTypeList.length > 0) {
      universeInstanceTypeList.unshift(<option key="" value="">Select</option>);
    }
    return (
      <div>
        <Modal show={visible} onHide={onClose}>
          <form name="UniverseModalForm" onSubmit=
                  {this.props.type==="Create" ? handleSubmit(this.submitCreateUniverse) :
                  handleSubmit(this.submitEditUniverse)}>
            <Modal.Header closeButton>
              <Modal.Title>{this.props.type} Universe </Modal.Title>
            </Modal.Header>
            <Modal.Body>
              <Field name="universeName" type="text" component={YBInput} label="Universe Name" />
              <Field name="provider" type="select" component={YBSelect} label="Provider"
                     options={universeProviderList} onChange={this.providerChanged}
                     defaultValue={this.state.providerSelected}
                     value={this.state.providerSelected}
              />
              <Field name="regionList" component={YBMultiSelect}
                     label="Regions" options={universeRegionList}
                     onChange={this.regionListChanged}
                     value={this.state.regionSelected} multi={this.state.azCheckState}/>

              <Field name="numNodes" type="text" component={YBNumericInput}
                     label="Number Of Nodes"
                     value={this.state.numNodes} onChange={this.state.numNodesChanged} />
              <div className="universeFormSplit">
                Advanced
              </div>
              <Field name="isMultiAZ" type="checkbox" component={YBCheckBox}
                     label="Multi AZ" onClick={azCheckStateChanged}/>
              <Field name="instanceType" type="select" component={YBSelect} label="Instance Type"
                     options={universeInstanceTypeList} onChange={this.instanceTypeChanged}
                     defaultValue={this.state.instanceTypeSelected}
                     value={this.state.instanceTypeSelected}
              />
              <Field name="serverPackage" type="text" component={YBInput}
                     label="Server Package" defaultValue={this.state.serverPackage} />
            </Modal.Body>
            <Modal.Footer>
              <button type="submit" className="btn btn-default btn-success btn-block" >
                {this.props.type} Universe
              </button>
            </Modal.Footer>
          </form>
        </Modal>
      </div>
    )
  }
}
