// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "fieldlengthfeature.h"
#include "valuefeature.h"
#include "utils.h"
#include <vespa/searchlib/fef/itermdata.h>
#include <vespa/searchlib/fef/featurenamebuilder.h>
#include <vespa/searchlib/fef/fieldinfo.h>
#include <vespa/searchlib/fef/fieldtype.h>
#include <vespa/vespalib/util/stringfmt.h>

using namespace search::fef;

namespace search {
namespace features {

FieldLengthExecutor::
FieldLengthExecutor(const IQueryEnvironment &env,
                    uint32_t fieldId)
    : FeatureExecutor(),
      _fieldHandles(),
      _md(nullptr)
{
    for (uint32_t i = 0; i < env.getNumTerms(); ++i) {
        TermFieldHandle handle = util::getTermFieldHandle(env, i, fieldId);
        if (handle != IllegalHandle) {
            _fieldHandles.push_back(handle);
        }
    }
}

void
FieldLengthExecutor::execute(uint32_t docId)
{
    uint32_t val = 0;
    bool validVal = false;
    for (std::vector<TermFieldHandle>::const_iterator
             hi = _fieldHandles.begin(), hie = _fieldHandles.end();
         hi != hie; ++hi)
    {
        const TermFieldMatchData &tfmd = *_md->resolveTermField(*hi);
        if (tfmd.getDocId() == docId) {
            FieldPositionsIterator it = tfmd.getIterator();
            if (it.valid()) {
                if (val < it.getFieldLength())
                    val = it.getFieldLength();
                validVal = true;
            }
        }
    }
    if (!validVal) {
        val = fef::FieldPositionsIterator::UNKNOWN_LENGTH;
    }
    feature_t value = val;
    outputs().set_number(0, value); // field length
}

void
FieldLengthExecutor::handle_bind_match_data(const MatchData &md)
{
    _md = &md;
}

FieldLengthBlueprint::FieldLengthBlueprint()
    : Blueprint("fieldLength"),
      _field(NULL)
{
}

void
FieldLengthBlueprint::visitDumpFeatures(const IIndexEnvironment &,
                                        IDumpFeatureVisitor &) const
{
}

bool
FieldLengthBlueprint::setup(const IIndexEnvironment &env,
                            const ParameterList &params)
{
    (void) env;
    _field = params[0].asField();
    describeOutput("out", "The length of this field.");
    return true;
}

Blueprint::UP
FieldLengthBlueprint::createInstance() const
{
    return Blueprint::UP(new FieldLengthBlueprint());
}

FeatureExecutor &
FieldLengthBlueprint::createExecutor(const IQueryEnvironment &env, vespalib::Stash &stash) const
{
    if (_field == 0) {
        std::vector<feature_t> values;
        values.push_back(fef::FieldPositionsIterator::UNKNOWN_LENGTH);
        return stash.create<ValueExecutor>(values);
    }
    return stash.create<FieldLengthExecutor>(env, _field->id());
}

}}
